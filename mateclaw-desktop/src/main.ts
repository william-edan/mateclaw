import { app, BrowserWindow, Menu, shell } from 'electron'
import { spawn, type ChildProcess } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const DEFAULT_PORT = 18088
const STARTUP_TIMEOUT_MS = 150_000

// Shared contract: native host name + fixed extension id (must match the bridge / extension side).
const NATIVE_HOST_NAME = 'com.mateclaw.browser_bridge'
const EXTENSION_ID = 'bjdhmojdiahokgfcaahphcjgcnffbonf'
const NATIVE_HOST_MANIFEST_FILE = `${NATIVE_HOST_NAME}.json`

// Chromium-family browsers that share the same NativeMessagingHosts manifest contract.
// All point to the SAME manifest path; a per-browser failure must not abort the others.
const NATIVE_HOST_REGISTRY_BRANCHES = [
  { label: 'Chrome', key: `HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Edge', key: `HKCU\\Software\\Microsoft\\Edge\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Brave', key: `HKCU\\Software\\BraveSoftware\\Brave-Browser\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
]

// Identity-probe endpoint: unauthenticated, MateClaw-specific. Used to confirm an
// already-listening server on the target port is actually our backend before reusing it.
const IDENTITY_PROBE_PATH = '/api/v1/setup/status'

// serverOwnedByDesktop: true only while we own a backend child we spawned ourselves.
// intentionalShutdown: set in before-quit so the child 'exit' handler can distinguish a
// user-initiated quit from a crash (the latter triggers auto-restart with backoff).
let mainWindow: BrowserWindow | null = null
let splashWindow: BrowserWindow | null = null
let serverProcess: ChildProcess | null = null
let serverOwnedByDesktop = false
let intentionalShutdown = false
let backendRestartAttempts = 0
const MAX_BACKEND_RESTART_ATTEMPTS = 5

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

function resolvePort(): number {
  const raw = process.env.MATECLAW_DESKTOP_PORT || process.env.SERVER_PORT
  const parsed = raw ? Number.parseInt(raw, 10) : DEFAULT_PORT
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_PORT
}

function localServerUrl(port: number): string {
  return `http://localhost:${port}`
}

function appRoot(): string {
  return app.isPackaged ? process.resourcesPath : path.resolve(__dirname, '..', 'resources')
}

function appContentRoot(): string {
  return app.isPackaged ? app.getAppPath() : path.resolve(__dirname, '..')
}

function serverJarPath(): string {
  const override = process.env.MATECLAW_SERVER_JAR
  if (override && fs.existsSync(override)) return override
  return path.join(appRoot(), 'server', 'mateclaw-server.jar')
}

function javaExecutable(): string {
  const override = process.env.MATECLAW_JAVA
  if (override && fs.existsSync(override)) return override

  const runtimeRoot = path.join(appRoot(), 'runtime')
  const packagedJavaw = path.join(runtimeRoot, 'bin', process.platform === 'win32' ? 'javaw.exe' : 'java')
  if (fs.existsSync(packagedJavaw)) return packagedJavaw

  const packagedJava = path.join(runtimeRoot, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  if (fs.existsSync(packagedJava)) return packagedJava

  const javaHome = process.env.JAVA_HOME
  if (javaHome) {
    const javaw = path.join(javaHome, 'bin', process.platform === 'win32' ? 'javaw.exe' : 'java')
    if (fs.existsSync(javaw)) return javaw
    const java = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    if (fs.existsSync(java)) return java
  }

  return process.platform === 'win32' ? 'javaw.exe' : 'java'
}

function userDataDir(): string {
  const override = process.env.MATECLAW_DESKTOP_DATA_DIR
  if (override) return override
  return app.getPath('userData')
}

function createSplashWindow(): void {
  splashWindow = new BrowserWindow({
    width: 420,
    height: 280,
    resizable: false,
    frame: false,
    show: false,
    backgroundColor: '#f7f1e8',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
    },
  })

  void splashWindow.loadFile(path.join(appContentRoot(), 'splash', 'index.html'))
  splashWindow.once('ready-to-show', () => splashWindow?.show())
  splashWindow.on('closed', () => {
    splashWindow = null
  })
}

function createMainWindow(port: number): BrowserWindow {
  const win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 1024,
    minHeight: 680,
    show: false,
    backgroundColor: '#f7f1e8',
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.js'),
    },
  })

  if (app.isPackaged && process.platform !== 'darwin') {
    win.setMenu(null)
  }

  win.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url)
    return { action: 'deny' }
  })

  win.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith(`http://127.0.0.1:${port}`) && !url.startsWith(`http://localhost:${port}`)) {
      event.preventDefault()
      void shell.openExternal(url)
    }
  })

  win.once('ready-to-show', () => {
    splashWindow?.close()
    win.show()
  })

  win.on('closed', () => {
    mainWindow = null
  })

  return win
}

function ping(url: string, timeoutMs = 1200): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.get(url, { timeout: timeoutMs }, (res) => {
      res.resume()
      resolve(Boolean(res.statusCode && res.statusCode >= 200 && res.statusCode < 500))
    })
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
    req.on('error', () => resolve(false))
  })
}

/**
 * Identity probe: confirms that whatever is listening on the port is actually a MateClaw
 * backend, not some unrelated service that happens to occupy the same port. Hits the
 * unauthenticated MateClaw-specific endpoint {@link IDENTITY_PROBE_PATH} and checks the
 * body for the unified R-envelope ("code") carrying the setup "initialized" flag. Any
 * mismatch, non-2xx, or parse failure resolves to false so the caller does not reuse it.
 */
function probeIsMateClaw(port: number, timeoutMs = 2000): Promise<boolean> {
  return new Promise((resolve) => {
    const req = http.get(`${localServerUrl(port)}${IDENTITY_PROBE_PATH}`, { timeout: timeoutMs }, (res) => {
      if (!res.statusCode || res.statusCode < 200 || res.statusCode >= 300) {
        res.resume()
        resolve(false)
        return
      }
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (chunk) => {
        body += chunk
        if (body.length > 64_000) req.destroy() // guard against an unexpectedly large body
      })
      res.on('end', () => {
        try {
          const parsed = JSON.parse(body) as { code?: unknown; data?: { initialized?: unknown } }
          // Both the R-envelope ("code") and the setup payload ("initialized") must be present.
          const ok = typeof parsed?.code === 'number' && typeof parsed?.data?.initialized === 'boolean'
          resolve(ok)
        } catch {
          resolve(false)
        }
      })
    })
    req.on('timeout', () => {
      req.destroy()
      resolve(false)
    })
    req.on('error', () => resolve(false))
  })
}

async function waitForServer(port: number): Promise<void> {
  const url = localServerUrl(port)
  const deadline = Date.now() + STARTUP_TIMEOUT_MS
  while (Date.now() < deadline) {
    if (await ping(url)) return
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error(`化帆AI backend did not become ready within ${Math.round(STARTUP_TIMEOUT_MS / 1000)}s.`)
}

function startBackend(port: number): void {
  const jar = serverJarPath()
  if (!fs.existsSync(jar)) {
    throw new Error(`Backend JAR not found: ${jar}`)
  }

  const dataDir = userDataDir()
  const logsDir = path.join(dataDir, 'logs')
  fs.mkdirSync(logsDir, { recursive: true })

  const out = fs.openSync(path.join(logsDir, 'mateclaw.out.log'), 'a')
  const err = fs.openSync(path.join(logsDir, 'mateclaw.err.log'), 'a')

  const args = [
    '-Dfile.encoding=UTF-8',
    '-Duser.timezone=Asia/Shanghai',
    '-jar',
    jar,
    `--server.port=${port}`,
  ]

  const child = spawn(javaExecutable(), args, {
    cwd: dataDir,
    env: {
      ...process.env,
      MATECLAW_DESKTOP: 'true',
    },
    stdio: ['ignore', out, err],
    windowsHide: true,
  })
  serverProcess = child
  serverOwnedByDesktop = true

  child.once('exit', (code, signal) => {
    const wasOwned = serverOwnedByDesktop
    serverProcess = null
    serverOwnedByDesktop = false
    // Distinguish a user-initiated quit (before-quit set the flag) from a crash. Only a
    // crash of a backend WE owned triggers the supervised auto-restart with backoff.
    if (!intentionalShutdown && wasOwned) {
      console.warn(`[mateclaw] backend exited unexpectedly (code=${code}, signal=${signal}); scheduling restart`)
      void restartBackendWithBackoff(port)
    }
  })
}

/**
 * Crash guard for the desktop-owned backend. On an unexpected child exit, restarts the
 * backend with exponential-ish backoff up to {@link MAX_BACKEND_RESTART_ATTEMPTS} times,
 * health-checking each attempt. A successful health check resets the attempt counter.
 * If every attempt fails, reports a visible error to the main (or splash) window.
 */
async function restartBackendWithBackoff(port: number): Promise<void> {
  while (backendRestartAttempts < MAX_BACKEND_RESTART_ATTEMPTS) {
    if (intentionalShutdown) return // app is quitting; abandon the restart loop
    backendRestartAttempts += 1
    const attempt = backendRestartAttempts
    const delayMs = Math.min(30_000, 1000 * 2 ** (attempt - 1)) // 1s,2s,4s,8s,16s (capped 30s)
    console.warn(`[mateclaw] backend restart attempt ${attempt}/${MAX_BACKEND_RESTART_ATTEMPTS} in ${delayMs}ms`)
    await new Promise((resolve) => setTimeout(resolve, delayMs))
    if (intentionalShutdown) return
    try {
      startBackend(port)
      await waitForServer(port)
      console.log(`[mateclaw] backend restart attempt ${attempt} succeeded`)
      backendRestartAttempts = 0 // recovered; reset budget for the next crash
      return
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      console.warn(`[mateclaw] backend restart attempt ${attempt} failed: ${message}`)
      // If startBackend spawned a child that never became healthy (waitForServer timed
      // out), it is still alive — kill it before the next attempt so we don't leak a
      // second JVM fighting over the H2 lock. We null serverProcess first so this kill
      // does not re-trigger the crash-restart handler.
      const stale = serverProcess
      serverProcess = null
      serverOwnedByDesktop = false
      if (stale && !stale.killed && stale.pid) {
        if (os.platform() === 'win32') {
          spawn('taskkill', ['/pid', String(stale.pid), '/T', '/F'], { windowsHide: true })
        } else {
          stale.kill('SIGTERM')
        }
      }
      // Loop continues to the next attempt.
    }
  }
  const message =
    `化帆AI 后端连续 ${MAX_BACKEND_RESTART_ATTEMPTS} 次重启失败，已停止自动重启。请查看日志后重启应用。`
  console.error(`[mateclaw] ${message}`)
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('startup-error', message)
  } else if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.webContents.send('startup-error', message)
  }
}

async function ensureBackend(port: number): Promise<void> {
  if (await ping(localServerUrl(port))) {
    // Something is ALREADY listening on this port. Only reuse it if an identity probe
    // confirms it is a MateClaw backend — otherwise an unrelated service squatting on
    // 18088 would silently break auto-pairing (no bridge.yaml, no Native Messaging).
    if (await probeIsMateClaw(port)) {
      // Reusing a real MateClaw server. If it was not started by this desktop app
      // (e.g. a dev `mvn spring-boot:run`), it lacks MATECLAW_DESKTOP=true, so
      // DesktopBridgeProvisioner never runs and ~/.mateclaw/bridge.yaml is never
      // written; browser-extension auto-connect still won't work in that case.
      console.warn(
        `[mateclaw] Reusing an existing MateClaw server on ${port}. If it was not started ` +
          'by this app (e.g. a dev server), browser-extension auto-connect will not work — ' +
          'stop it and restart so the desktop server can provision bridge.yaml.',
      )
      return
    }
    // Port is occupied by a non-MateClaw service. We cannot auto-pair against it.
    // Surface a visible error to the splash window instead of silently reusing it.
    throw new Error(
      `端口 ${port} 已被其他程序占用，且不是 化帆AI 后端，无法自动配对。` +
        `请关闭占用该端口的程序后重启，或设置环境变量 MATECLAW_DESKTOP_PORT 指定其他端口。`,
    )
  }
  startBackend(port)
  await waitForServer(port)
}

/** Absolute path to the bridge.exe shipped inside the desktop package. */
function bridgeExecutablePath(): string {
  return path.join(appRoot(), 'bridge', 'bridge.exe')
}

/**
 * Registers the Chrome Native Messaging host so the bundled bridge.exe is reachable
 * the moment the desktop app is installed. Idempotent: rewrites the manifest and the
 * HKCU registry value on every boot, which is safe to repeat.
 *
 * Windows only for now. Failures are logged and never block startup.
 */
function ensureNativeHostRegistered(): void {
  try {
    if (process.platform !== 'win32') {
      // TODO: implement NativeMessagingHosts registration for macOS (~/Library/Application Support)
      // and Linux (~/.config/google-chrome/NativeMessagingHosts).
      console.warn(`[native-host] skipped: registration is only implemented on Windows (platform=${process.platform})`)
      return
    }

    const bridgePath = bridgeExecutablePath()
    if (!fs.existsSync(bridgePath)) {
      console.warn(`[native-host] bridge.exe not found, skipping registration: ${bridgePath}`)
      return
    }

    const localAppData = process.env.LOCALAPPDATA
    if (!localAppData) {
      console.warn('[native-host] LOCALAPPDATA is not set, skipping registration')
      return
    }

    const installDir = path.join(localAppData, 'MateClaw')
    fs.mkdirSync(installDir, { recursive: true })
    const manifestPath = path.join(installDir, NATIVE_HOST_MANIFEST_FILE)

    const manifest = {
      name: NATIVE_HOST_NAME,
      description: 'MateClaw Browser Agent Native Host',
      path: bridgePath,
      type: 'stdio',
      allowed_origins: [`chrome-extension://${EXTENSION_ID}/`],
    }
    fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

    // Register the same manifest under Chrome, Edge and Brave. Each branch is isolated:
    // a failure in one (e.g. the browser's registry root does not exist) never aborts the others.
    for (const branch of NATIVE_HOST_REGISTRY_BRANCHES) {
      try {
        const reg = spawn('reg', ['add', branch.key, '/ve', '/d', manifestPath, '/f'], { windowsHide: true })
        reg.on('error', (error) => {
          console.warn(
            `[native-host] ${branch.label} reg add failed to launch: ` +
              `${error instanceof Error ? error.message : String(error)}`,
          )
        })
        reg.on('exit', (code) => {
          if (code === 0) {
            console.log(`[native-host] registered ${branch.label} ${NATIVE_HOST_NAME} -> ${manifestPath}`)
          } else {
            console.warn(`[native-host] ${branch.label} reg add exited with code ${code}`)
          }
        })
      } catch (error) {
        console.warn(
          `[native-host] ${branch.label} registration error: ` +
            `${error instanceof Error ? error.message : String(error)}`,
        )
      }
    }
  } catch (error) {
    console.warn(`[native-host] registration error: ${error instanceof Error ? error.message : String(error)}`)
  }
}

function installMenu(port: number): void {
  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: '化帆AI',
      submenu: [
        {
          label: '打开 化帆AI',
          click: () => {
            mainWindow?.focus()
            void mainWindow?.loadURL(localServerUrl(port))
          },
        },
        {
          label: 'Open Logs Folder',
          click: () => {
            void shell.openPath(path.join(userDataDir(), 'logs'))
          },
        },
        { type: 'separator' },
        { role: 'quit' },
      ],
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { role: 'togglefullscreen' },
      ],
    },
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

async function boot(): Promise<void> {
  const lock = app.requestSingleInstanceLock()
  if (!lock) {
    app.quit()
    return
  }

  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
    }
  })

  await app.whenReady()
  const port = resolvePort()
  installMenu(port)
  ensureNativeHostRegistered()
  if (!app.isPackaged) {
    installMenu(port)
  } else if (process.platform !== 'darwin') {
    Menu.setApplicationMenu(null)
  }
  createSplashWindow()

  try {
    await ensureBackend(port)
    mainWindow = createMainWindow(port)
    await mainWindow.loadURL(localServerUrl(port))
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    splashWindow?.webContents.send('startup-error', message)
    if (splashWindow) {
      splashWindow.setClosable(true)
      splashWindow.setResizable(false)
    }
  }
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', () => {
  // Mark this as a deliberate shutdown so the child 'exit' handler does NOT treat the
  // ensuing termination as a crash and try to auto-restart the backend.
  intentionalShutdown = true
  if (serverOwnedByDesktop && serverProcess && !serverProcess.killed) {
    if (os.platform() === 'win32') {
      spawn('taskkill', ['/pid', String(serverProcess.pid), '/T', '/F'], { windowsHide: true })
    } else {
      serverProcess.kill('SIGTERM')
    }
  }
})

void boot()
