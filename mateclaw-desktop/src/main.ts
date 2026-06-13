import { app, BrowserWindow, Menu, shell } from 'electron'
import { spawn, type ChildProcess } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const DEFAULT_PORT = 18088
const STARTUP_TIMEOUT_MS = 150_000

let mainWindow: BrowserWindow | null = null
let splashWindow: BrowserWindow | null = null
let serverProcess: ChildProcess | null = null
let serverOwnedByDesktop = false

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
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.js'),
    },
  })

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

  child.once('exit', () => {
    serverProcess = null
    serverOwnedByDesktop = false
  })
}

async function ensureBackend(port: number): Promise<void> {
  if (await ping(localServerUrl(port))) return
  startBackend(port)
  await waitForServer(port)
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
  if (serverOwnedByDesktop && serverProcess && !serverProcess.killed) {
    if (os.platform() === 'win32') {
      spawn('taskkill', ['/pid', String(serverProcess.pid), '/T', '/F'], { windowsHide: true })
    } else {
      serverProcess.kill('SIGTERM')
    }
  }
})

void boot()
