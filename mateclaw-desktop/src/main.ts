import { app, BrowserWindow, Menu, dialog, shell } from 'electron'
import { spawn, type ChildProcess } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const DEFAULT_PORT = 18088
// 首启要跑大量 Flyway 迁移 + 种子,慢机/慢盘上 150s 不够,提到 300s。
const STARTUP_TIMEOUT_MS = 300_000

// Shared contract: native host name + fixed extension id (must match the bridge / extension side).
const NATIVE_HOST_NAME = 'com.mateclaw.browser_bridge'
const EXTENSION_ID = 'bjdhmojdiahokgfcaahphcjgcnffbonf'
const NATIVE_HOST_MANIFEST_FILE = `${NATIVE_HOST_NAME}.json`

// Chromium-family browsers that share the same NativeMessagingHosts manifest contract.
// All point to the SAME manifest path; a per-browser failure must not abort the others.
// 修复 6:除 Chrome/Edge/Brave 外,补常见 Chromium 衍生(国内浏览器 + Chrome 各通道),
// 这些浏览器在干净机上同样需要写 HKCU\...\NativeMessagingHosts\<host> 才能拉起原生桥。
// 注册表根路径不存在的分支会自然失败,但每个分支独立,互不影响。
const NATIVE_HOST_REGISTRY_BRANCHES = [
  { label: 'Chrome', key: `HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Chrome Beta', key: `HKCU\\Software\\Google\\Chrome Beta\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Chrome Dev', key: `HKCU\\Software\\Google\\Chrome Dev\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Chrome Canary', key: `HKCU\\Software\\Google\\Chrome SxS\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Edge', key: `HKCU\\Software\\Microsoft\\Edge\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Brave', key: `HKCU\\Software\\BraveSoftware\\Brave-Browser\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: '360安全浏览器', key: `HKCU\\Software\\360\\360se6\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: '360极速浏览器', key: `HKCU\\Software\\360Chrome\\Chrome\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'QQ浏览器', key: `HKCU\\Software\\Tencent\\QQBrowser\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: '搜狗浏览器', key: `HKCU\\Software\\SogouExplorer\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Vivaldi', key: `HKCU\\Software\\Vivaldi\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
  { label: 'Opera', key: `HKCU\\Software\\Opera Software\\NativeMessagingHosts\\${NATIVE_HOST_NAME}` },
]

// Identity-probe endpoint: unauthenticated, MateClaw-specific. Used to confirm an
// already-listening server on the target port is actually our backend before reusing it.
const IDENTITY_PROBE_PATH = '/api/v1/setup/status'

// 契约 4(常驻 bridge feature-flag,默认 OFF / 保底回退)。
// 仅当 MATECLAW_RESIDENT_BRIDGE 显式为 '1' / 'true'(大小写不敏感)时,桌面壳才会:
//   - 在后端就绪后常驻拉起 bridge.exe(`run --resident`),由它在本地 loopback(组1:
//     ws://127.0.0.1:18077)上服务扩展,根除 Chrome 经 Native Messaging 旁路拉起 bridge;
//   - 跳过 / 清除 Native Messaging host 注册(否则 Chrome 仍会按 manifest 自行拉起一个
//     竞争的 bridge 实例)。
// flag OFF(默认)时本文件行为与今天【完全一致】:不拉常驻 bridge、照常
// ensureNativeHostRegistered() 注册原生桥、走现有 direct/native 路径。纯增量、零回归。
const RESIDENT_BRIDGE_ENV = 'MATECLAW_RESIDENT_BRIDGE'
function isResidentBridgeEnabled(): boolean {
  // 默认 ON(根治:桌面常驻 bridge 锚)。仅当显式设 MATECLAW_RESIDENT_BRIDGE=0/false/no/off
  // 时才回退到旧 native 路(出问题的应急开关);未设/空=ON。
  const raw = (process.env[RESIDENT_BRIDGE_ENV] ?? '').trim().toLowerCase()
  return !(raw === '0' || raw === 'false' || raw === 'no' || raw === 'off')
}

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

// 常驻 bridge 子进程状态(仅在 feature-flag ON 时使用),与后端进程对称。
// bridgeOwnedByDesktop:仅当我们自己拉起的常驻 bridge 仍在运行时为 true。
// intentionalShutdown 标志在 bridge 与 backend 之间【复用】——before-quit 一次置位即可
// 让两个 exit 处理器都把随后的退出识别为"用户主动退出"而非崩溃。
let bridgeProcess: ChildProcess | null = null
let bridgeOwnedByDesktop = false
let bridgeRestartAttempts = 0
const MAX_BRIDGE_RESTART_ATTEMPTS = 5

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

/**
 * 致命启动错误的统一可见兜底。无论 preload 是否成功注入,都同时:
 *  1) 通过 IPC 推到 splash/main 窗口(走 preload 的 mateclaw-startup-error 渲染);
 *  2) 弹出原生 dialog.showErrorBox —— 这一步不依赖 preload/渲染进程,
 *     保证干净机上即使 preload 缺失也能看到错误,而不是永远转圈。
 */
function reportFatalStartupError(message: string): void {
  console.error(`[mateclaw] ${message}`)
  try {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('startup-error', message)
    } else if (splashWindow && !splashWindow.isDestroyed()) {
      splashWindow.webContents.send('startup-error', message)
      // 让用户能关掉转圈中的 splash,而不是被迫强杀进程。
      splashWindow.setClosable(true)
    }
  } catch (sendError) {
    console.warn(`[mateclaw] failed to forward startup error to window: ${String(sendError)}`)
  }
  // 原生对话框兜底:不依赖任何渲染进程/preload,任何情况下都可见。
  try {
    dialog.showErrorBox('化帆AI 启动失败', message)
  } catch (dialogError) {
    console.warn(`[mateclaw] failed to show native error dialog: ${String(dialogError)}`)
  }
}

// 非致命警告(如原生桥缺失)缓冲区:窗口可能尚未就绪时先缓存,待 splash/main ready 后冲刷,
// 保证用户能看到,而不是只落在 console。
const pendingStartupWarnings: string[] = []

/**
 * 上报一条非致命启动警告(不阻断启动)。能找到窗口就立即 IPC 推送渲染;无论窗口在不在,
 * 都缓存一份,待窗口 ready 后由 flushPendingStartupWarnings 补发。
 */
function reportStartupWarning(message: string): void {
  console.warn(`[mateclaw] ${message}`)
  pendingStartupWarnings.push(message)
  flushPendingStartupWarnings()
  // 原生对话框兜底:startup-warning 的 IPC 渲染依赖 splash(main ready 后即关闭)且 SPA 暂未监听,
  // 故对"原生桥缺失"这类重要但非致命的警告,用非阻断的 showMessageBox 确保一定可见。
  try {
    void dialog.showMessageBox({ type: 'warning', title: '化帆AI', message })
  } catch (dialogError) {
    console.warn(`[mateclaw] failed to show native warning dialog: ${String(dialogError)}`)
  }
}

/** 把缓冲的非致命警告推到当前可用窗口(main 优先,其次 splash)。推成功的从缓冲区移除。 */
function flushPendingStartupWarnings(): void {
  if (pendingStartupWarnings.length === 0) return
  const target =
    mainWindow && !mainWindow.isDestroyed()
      ? mainWindow
      : splashWindow && !splashWindow.isDestroyed()
        ? splashWindow
        : null
  if (!target) return
  try {
    for (const message of pendingStartupWarnings) {
      target.webContents.send('startup-warning', message)
    }
    pendingStartupWarnings.length = 0
  } catch (error) {
    console.warn(`[mateclaw] failed to flush startup warnings: ${String(error)}`)
  }
}

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

  // 打包(发行)模式:内置 runtime 是唯一可信来源。干净机通常没有系统 Java,
  // 回退到裸 'javaw.exe' 或系统 JAVA_HOME 只会更隐晦地失败(ENOENT/版本不符/无依赖)。
  // 因此直接抛可见错误,提示重装,而不是静默回退。
  if (app.isPackaged) {
    throw new Error(
      `内置运行时缺失:未找到 ${packagedJavaw}。安装可能不完整或被杀软删除,请重新安装 化帆AI。`,
    )
  }

  // 非打包(开发)模式才允许回退到系统 JAVA_HOME / PATH,方便本地 `pnpm start` 调试。
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
      // 与 main 窗口一致注入 preload,使 startup-error IPC 能在 splash 上渲染。
      // 即便 preload 缺失,reportFatalStartupError 仍有 dialog 兜底,不会永远转圈。
      preload: path.join(__dirname, 'preload.js'),
    },
  })

  void splashWindow.loadFile(path.join(appContentRoot(), 'splash', 'index.html'))
  splashWindow.once('ready-to-show', () => {
    splashWindow?.show()
    // 窗口就绪后冲刷在窗口创建前缓冲的非致命警告(如原生桥缺失)。
    flushPendingStartupWarnings()
  })
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
    // main 窗口就绪后,把仍未送达的非致命警告补发到主界面(splash 已关闭时这是最后机会)。
    flushPendingStartupWarnings()
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

  // 契约 3:H2 绝对路径 url。中文/非 ASCII cwd 下相对路径解析常失败,这里用 dataDir
  // 的正斜杠绝对路径强制指向 <dataDir>/data/huafanai。application-desktop.yml 内保留
  // 相对路径作回退,命令行参数优先级更高、不冲突。
  const h2AbsoluteBase = path.join(dataDir, 'data', 'huafanai').replace(/\\/g, '/')
  const h2Url =
    `jdbc:h2:file:${h2AbsoluteBase};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`

  const args = [
    '-Dfile.encoding=UTF-8',
    // 契约 8:强制 JVM 内部各编码为 UTF-8,避免干净机默认 GBK 导致中文乱码/路径解析异常。
    '-Dsun.jnu.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    '-Duser.timezone=Asia/Shanghai',
    '-jar',
    jar,
    `--server.port=${port}`,
    // 契约 1:激活 desktop profile(组C 提供 application-desktop.yml:H2 数据源、INFO 日志、关 H2 console、绝不绑 MySQL)。
    '--spring.profiles.active=desktop',
    // 契约 3:注入 H2 绝对路径 url(覆盖 profile 内的相对路径回退)。
    `--spring.datasource.url=${h2Url}`,
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

  // 修复 3:spawn 失败(如 ENOENT —— javaw 路径不对/被删)要明确可见,不能靠 150/300s 超时拖出来。
  child.once('error', (spawnError) => {
    const message =
      `无法启动 化帆AI 后端进程:${spawnError instanceof Error ? spawnError.message : String(spawnError)}。` +
      '请确认内置运行时完整(可尝试重新安装)。'
    // 这是一次失败的 spawn,清掉 owned 标记,避免 exit 处理器把它当崩溃去重启。
    if (serverProcess === child) {
      serverProcess = null
      serverOwnedByDesktop = false
    }
    reportFatalStartupError(message)
  })

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
 * 强杀仍在运行的、由我们启动的后端子 JVM(用于健康检查超时后的清理)。先把 serverProcess
 * 置空再杀,避免触发 exit 处理器的崩溃-重启逻辑。否则一个超时未就绪的旧 JVM 会和下一次
 * 启动的新 JVM 抢同一个 H2 库锁(huafanai.mv.db)。
 */
function killStaleBackend(): void {
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
      // second JVM fighting over the H2 lock.
      killStaleBackend()
      // Loop continues to the next attempt.
    }
  }
  const message =
    `化帆AI 后端连续 ${MAX_BACKEND_RESTART_ATTEMPTS} 次重启失败，已停止自动重启。请查看日志后重启应用。`
  reportFatalStartupError(message)
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
    // 修复 4:端口被非 化帆AI 程序占用。保持端口 18088(与扩展白名单一致),不静默换端口。
    // 弹可见对话框提示用户关闭占用该端口的程序,而不是静默 throw。throw 仍保留,让 boot()
    // 的 catch 终止后续启动流程(此时 reportFatalStartupError 已展示过对话框,catch 里会再次
    // 走 IPC 兜底,两者幂等)。
    throw new Error(
      `端口 ${port} 已被其他程序占用，且不是 化帆AI 后端,无法自动配对。` +
        `请关闭占用 ${port} 端口的程序后重试,或设置环境变量 MATECLAW_DESKTOP_PORT 指定其他端口。`,
    )
  }
  startBackend(port)
  try {
    await waitForServer(port)
  } catch (error) {
    // 修复 5:首启健康检查超时后,startBackend 启的子 JVM 可能仍在跑(只是没就绪)。
    // 必须强杀它,否则用户重启应用时新旧两个 JVM 会抢同一个 H2 库锁导致 database closed。
    killStaleBackend()
    throw error
  }
}

/** Absolute path to the bridge.exe shipped inside the desktop package. */
function bridgeExecutablePath(): string {
  return path.join(appRoot(), 'bridge', 'bridge.exe')
}

/**
 * 常驻拉起 bridge.exe(契约 4,仅 feature-flag ON 时调用)。镜像 startBackend 的进程骨架:
 *   - `run --resident`:显式 run 子命令(bridge decideMode 第 2 优先级,稳定走 run 路径),
 *     --resident 让 bridge 在本地 loopback(组1:ws://127.0.0.1:18077)开 WS server 长驻,
 *     而不是当一次性 Native Messaging host(组1 负责识别该 flag;桌面侧只负责按约定传参)。
 *   - stdio: ['ignore', out, err]:【命脉】stdin=ignore。常驻模式下扩展不再经 stdin 喂帧,
 *     bridge 改从 loopback 收发;若给 stdin 接管道反而会让 Runner 的 stdin 读到 EOF 而误判收尾。
 *   - cwd + 日志:落到 userData/logs/bridge.{out,err}.log,与后端日志同目录,便于 Doctor 排障。
 *   - windowsHide: true:不弹黑窗。
 *
 * 时序:由调用方(boot)保证在 ensureBackend 成功(且 bridge.yaml 已由 DesktopBridgeProvisioner
 * 写出)之后才调用,避免 bridge 进 NO_TOKEN 空轮询。即便 bridge.yaml 略有延迟,bridge 自带
 * waitForToken 有界轮询(60s)兜底,不会立即自杀。
 */
function startBridge(): void {
  const exe = bridgeExecutablePath()
  if (!fs.existsSync(exe)) {
    // 常驻模式下 bridge.exe 缺失 = 扩展彻底连不上。非致命(后端/界面仍可用),走非阻断警告。
    reportStartupWarning(
      '常驻浏览器桥(bridge.exe)缺失,可能被杀毒软件拦截或安装不完整。' +
        '浏览器自动配对将无法工作,请将 化帆AI 安装目录加入杀软白名单后重新安装。',
    )
    return
  }

  const dataDir = userDataDir()
  const logsDir = path.join(dataDir, 'logs')
  fs.mkdirSync(logsDir, { recursive: true })

  const out = fs.openSync(path.join(logsDir, 'bridge.out.log'), 'a')
  const err = fs.openSync(path.join(logsDir, 'bridge.err.log'), 'a')

  const child = spawn(exe, ['run', '--resident'], {
    cwd: dataDir,
    env: {
      ...process.env,
      MATECLAW_DESKTOP: 'true',
    },
    // 命脉:stdin=ignore。常驻 bridge 不经 stdin 收帧(改走 loopback),给管道会触发误 EOF 收尾。
    stdio: ['ignore', out, err],
    windowsHide: true,
  })
  bridgeProcess = child
  bridgeOwnedByDesktop = true

  // spawn 失败(ENOENT / 被杀软删)要可见,但属非致命:不调 reportFatalStartupError(那会弹拦截式
  // 错误框、误导用户以为整个应用挂了)。常驻桥失败只影响扩展自动配对,走非阻断 startup-warning。
  child.once('error', (spawnError) => {
    const message =
      `无法启动常驻浏览器桥:${spawnError instanceof Error ? spawnError.message : String(spawnError)}。` +
      '浏览器自动配对将不可用,请确认安装完整(可尝试重新安装)。'
    if (bridgeProcess === child) {
      bridgeProcess = null
      bridgeOwnedByDesktop = false
    }
    reportStartupWarning(message)
  })

  child.once('exit', (code, signal) => {
    const wasOwned = bridgeOwnedByDesktop
    bridgeProcess = null
    bridgeOwnedByDesktop = false
    // 复用 intentionalShutdown:用户主动退出时不重启;只有我们拥有的常驻 bridge 意外退出才补救。
    if (!intentionalShutdown && wasOwned) {
      console.warn(`[mateclaw] resident bridge exited unexpectedly (code=${code}, signal=${signal}); scheduling restart`)
      void restartBridgeWithBackoff()
    }
  })
}

/**
 * 强杀仍在运行的、由我们启动的常驻 bridge(用于退出 / 重启前清理)。先把 bridgeProcess
 * 置空再杀,避免触发 exit 处理器的崩溃-重启逻辑(与 killStaleBackend 同构)。
 */
function killStaleBridge(): void {
  const stale = bridgeProcess
  bridgeProcess = null
  bridgeOwnedByDesktop = false
  if (stale && !stale.killed && stale.pid) {
    if (os.platform() === 'win32') {
      spawn('taskkill', ['/pid', String(stale.pid), '/T', '/F'], { windowsHide: true })
    } else {
      stale.kill('SIGTERM')
    }
  }
}

/**
 * 常驻 bridge 崩溃守护(契约 4 / 5)。常驻 bridge 意外退出时按指数退避重启,最多
 * {@link MAX_BRIDGE_RESTART_ATTEMPTS} 次。
 *
 * 关键边界(契约 5 — 生命周期硬隔离):此处只重启【桌面壳↔常驻 bridge 进程】这一层。
 * "扩展↔loopback"的断开由 bridge 内部消化、绝不冒泡到这里;"bridge↔后端 WSS"的健康/重连
 * 也完全在 bridge 进程内自洽。桌面壳只在【整个 bridge 进程】真的死掉时才补一个新进程,
 * 不会因为某条 socket 抖动而误杀/误拉,从而不会向后端重发 HELLO、不触发 4409 单活替换。
 *
 * bridge 没有 HTTP 健康端点(它服务的是 WS loopback),无法像后端那样 waitForServer 探活;
 * 因此这里以"重启后进程仍存活一小段时间"作为成功判据:若新进程在 STABLE_MS 内没有再次退出,
 * 即视为恢复并清零预算。若进程一拉起就秒退(error/exit 把 bridgeProcess 清空),则进入下一次退避。
 */
async function restartBridgeWithBackoff(): Promise<void> {
  const STABLE_MS = 5_000 // 新进程存活满 5s 视为稳定恢复
  while (bridgeRestartAttempts < MAX_BRIDGE_RESTART_ATTEMPTS) {
    if (intentionalShutdown) return // 应用退出中,放弃重启
    bridgeRestartAttempts += 1
    const attempt = bridgeRestartAttempts
    const delayMs = Math.min(30_000, 1000 * 2 ** (attempt - 1)) // 1s,2s,4s,8s,16s(封顶 30s)
    console.warn(`[mateclaw] resident bridge restart attempt ${attempt}/${MAX_BRIDGE_RESTART_ATTEMPTS} in ${delayMs}ms`)
    await new Promise((resolve) => setTimeout(resolve, delayMs))
    if (intentionalShutdown) return
    // 防御性清理:若上一实例诡异残留(理论上 exit 已置空 bridgeProcess),先杀掉再拉新的,
    // 保证全局只有一个常驻 bridge 占用 loopback 18077(契约 4 单实例)。
    killStaleBridge()
    startBridge()
    // 等一小段时间看新进程是否站得住(startBridge 的 error/exit 会把 bridgeProcess 清空)。
    await new Promise((resolve) => setTimeout(resolve, STABLE_MS))
    if (intentionalShutdown) return
    if (bridgeProcess && bridgeOwnedByDesktop) {
      console.log(`[mateclaw] resident bridge restart attempt ${attempt} succeeded`)
      bridgeRestartAttempts = 0 // 恢复,重置下一次崩溃的预算
      return
    }
    console.warn(`[mateclaw] resident bridge restart attempt ${attempt} did not stay alive`)
    // 循环继续下一次退避。
  }
  // 多次仍拉不起常驻桥:非致命(后端/界面可用),走非阻断警告,不弹拦截式错误框。
  reportStartupWarning(
    `常驻浏览器桥连续 ${MAX_BRIDGE_RESTART_ATTEMPTS} 次启动失败,已停止自动重启。` +
      '浏览器自动配对暂不可用,请查看 bridge 日志后重启应用。',
  )
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
      // 修复 7:bridge.exe 缺失(可能被杀软删/安装不完整)不再只 console.warn。
      // 这会导致浏览器扩展的原生桥功能彻底不可用,必须让用户在 UI/splash 上看到。
      // 但这是非致命问题(后端/界面仍可用),所以走非阻断的 startup-warning,不弹拦截式对话框。
      reportStartupWarning(
        '扩展功能不可用:原生桥(bridge.exe)缺失,可能被杀毒软件拦截或安装不完整。' +
          '浏览器自动配对将无法工作,请将 化帆AI 安装目录加入杀软白名单后重新安装。',
      )
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

    // Register the same manifest under every supported Chromium-family branch. Each branch is
    // isolated: a failure in one (e.g. the browser's registry root does not exist) never aborts
    // the others.
    // 修复 6:reg add 的 key 与值都用引号包裹,manifestPath 可能含空格/非 ASCII(中文用户名)。
    // 用 cmd /c + windowsVerbatimArguments 让 reg 收到带引号的命令行,引号由 cmd 解析后去除,
    // 写入注册表的值不含字面引号(若用 spawn 数组参数直接传引号,引号会被当作值的一部分写入)。
    for (const branch of NATIVE_HOST_REGISTRY_BRANCHES) {
      try {
        const cmdLine = `reg add "${branch.key}" /ve /d "${manifestPath}" /f`
        const reg = spawn('cmd', ['/c', cmdLine], { windowsHide: true, windowsVerbatimArguments: true })
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

/**
 * 清除 Native Messaging host 注册(契约 4,仅 feature-flag ON 时调用)。
 *
 * 常驻 bridge 模式下,扩展改走桌面壳常驻的 loopback WS;若仍保留 HKCU\...\NativeMessagingHosts
 * 注册项,Chrome 会在扩展 connectNative 时按 manifest【旁路再拉起一个】竞争的 bridge 实例,
 * 与常驻实例抢后端 WSS(4409 单活替换、session 更替)。因此 ON 时把每个 Chromium 分支下的
 * host 注册项删掉,根除 Chrome 旁路拉起。
 *
 * 与 ensureNativeHostRegistered 对称:Windows-only、每分支独立(reg delete 失败/键不存在
 * 互不影响)、全程 best-effort 不阻断启动。保留磁盘上的 manifest 文件不动——它本身不会触发
 * 拉起(触发拉起的是注册表项),且 flag 关回 off 时 ensureNativeHostRegistered 会重写它。
 */
function clearNativeHostRegistration(): void {
  try {
    if (process.platform !== 'win32') {
      return
    }
    for (const branch of NATIVE_HOST_REGISTRY_BRANCHES) {
      try {
        const cmdLine = `reg delete "${branch.key}" /f`
        const reg = spawn('cmd', ['/c', cmdLine], { windowsHide: true, windowsVerbatimArguments: true })
        reg.on('error', (error) => {
          console.warn(
            `[native-host] ${branch.label} reg delete failed to launch: ` +
              `${error instanceof Error ? error.message : String(error)}`,
          )
        })
        reg.on('exit', (code) => {
          // code 0 = 删除成功;非 0 多为"键本就不存在"(该浏览器未注册过),属正常。
          if (code === 0) {
            console.log(`[native-host] cleared ${branch.label} ${NATIVE_HOST_NAME} (resident bridge mode)`)
          }
        })
      } catch (error) {
        console.warn(
          `[native-host] ${branch.label} clear error: ` +
            `${error instanceof Error ? error.message : String(error)}`,
        )
      }
    }
  } catch (error) {
    console.warn(`[native-host] clear error: ${error instanceof Error ? error.message : String(error)}`)
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
  // 契约 4(feature-flag,默认 OFF):
  //   OFF → 照旧注册 Native Messaging host(保留现有 native/direct 回退路径,行为零变化)。
  //   ON  → 不注册、且清除已有 host 注册项,根除 Chrome 经 Native Messaging 旁路拉起 bridge,
  //         改由后端就绪后 startBridge 常驻一个 bridge 实例独占后端 WSS。
  const residentBridge = isResidentBridgeEnabled()
  if (residentBridge) {
    console.log(`[mateclaw] resident bridge mode ENABLED (${RESIDENT_BRIDGE_ENV}); skipping native host registration`)
    clearNativeHostRegistration()
  } else {
    ensureNativeHostRegistered()
  }
  if (!app.isPackaged) {
    installMenu(port)
  } else if (process.platform !== 'darwin') {
    Menu.setApplicationMenu(null)
  }
  createSplashWindow()

  try {
    await ensureBackend(port)
    // 契约 4 时序:仅在后端就绪(且 DesktopBridgeProvisioner 已能写出 bridge.yaml)之后,
    // 且 feature-flag ON 时,才常驻拉起 bridge,避免它进 NO_TOKEN 空轮询。flag OFF 时此步
    // 完全跳过,行为与今天一致。startBridge 自身失败仅发非阻断警告,不影响主界面加载。
    if (residentBridge) {
      startBridge()
    }
    mainWindow = createMainWindow(port)
    await mainWindow.loadURL(localServerUrl(port))
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    // 修复 1/4:所有致命启动错误(含端口占用、后端超时、JRE 缺失)都走统一兜底:
    // 同时 IPC 推到 splash 渲染 + 原生 dialog.showErrorBox,保证无论 preload 在不在都可见,
    // 而不是永远转圈。
    reportFatalStartupError(message)
    if (splashWindow && !splashWindow.isDestroyed()) {
      splashWindow.setResizable(false)
    }
  }
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', () => {
  // Mark this as a deliberate shutdown so the child 'exit' handlers (backend AND resident
  // bridge) do NOT treat the ensuing termination as a crash and try to auto-restart.
  // intentionalShutdown 是两者共用的同一个标志,这里一次置位即可同时压制两条重启路径。
  intentionalShutdown = true
  if (serverOwnedByDesktop && serverProcess && !serverProcess.killed) {
    if (os.platform() === 'win32') {
      spawn('taskkill', ['/pid', String(serverProcess.pid), '/T', '/F'], { windowsHide: true })
    } else {
      serverProcess.kill('SIGTERM')
    }
  }
  // 追加杀常驻 bridge(仅 flag ON 时它才存在;OFF 时 bridgeOwnedByDesktop 恒为 false,
  // 此分支整段不进入,行为与今天一致)。与后端同构:taskkill /T /F 连同子进程一起清。
  if (bridgeOwnedByDesktop && bridgeProcess && !bridgeProcess.killed) {
    if (os.platform() === 'win32') {
      spawn('taskkill', ['/pid', String(bridgeProcess.pid), '/T', '/F'], { windowsHide: true })
    } else {
      bridgeProcess.kill('SIGTERM')
    }
  }
})

void boot()
