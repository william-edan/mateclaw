import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const desktopDir = path.resolve(path.dirname(__filename), '..')
const repoRoot = path.resolve(desktopDir, '..')

const serverTarget = path.join(repoRoot, 'mateclaw-server', 'target')
const resourcesDir = path.join(desktopDir, 'resources')
const serverResourceDir = path.join(resourcesDir, 'server')
const runtimeResourceDir = path.join(resourcesDir, 'runtime')
const bridgeResourceDir = path.join(resourcesDir, 'bridge')
const bridgeArtifact = path.join(repoRoot, 'mateclaw-browser-bridge', 'dist', 'cmd', 'bridge.exe')

function findServerJar() {
  const explicit = process.env.MATECLAW_SERVER_JAR
  if (explicit && fs.existsSync(explicit)) return path.resolve(explicit)

  const candidates = fs
    .readdirSync(serverTarget, { withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => path.join(serverTarget, entry.name))
    .filter((file) => /mateclaw-server-.*\.jar$/.test(path.basename(file)) && !file.endsWith('.original'))
    .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)

  if (!candidates.length) {
    throw new Error(`No mateclaw-server JAR found under ${serverTarget}. Run mvn -pl mateclaw-server -am package first.`)
  }
  return candidates[0]
}

function findJdkHome() {
  const candidates = [
    process.env.JDK_HOME,
    process.env.JAVA_HOME,
    'D:\\java\\JDK21',
    'C:\\Program Files\\Java\\jdk-21',
    'C:\\Program Files\\Eclipse Adoptium\\jdk-21',
    'C:\\Program Files\\Microsoft\\jdk-21',
  ].filter(Boolean)

  for (const candidate of candidates) {
    const jlink = path.join(candidate, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink')
    if (fs.existsSync(jlink)) return candidate
  }

  throw new Error('JDK 21 with jlink was not found. Set JAVA_HOME or JDK_HOME to a full JDK 21 installation.')
}

function copyServerJar() {
  const jar = findServerJar()
  fs.mkdirSync(serverResourceDir, { recursive: true })
  const dest = path.join(serverResourceDir, 'mateclaw-server.jar')
  fs.copyFileSync(jar, dest)
  console.log(`[desktop] copied server jar: ${jar} -> ${dest}`)
}

function copyBridgeBinary() {
  if (!fs.existsSync(bridgeArtifact)) {
    // 硬失败:bridge.exe 缺失曾被 console.warn 跳过,导致打出"无桥"的包,
    // 用户安装后浏览器 Native Messaging 静默不可用。改为 throw,缺桥即停。
    throw new Error(
      `[desktop] bridge.exe not found at ${bridgeArtifact}. ` +
        'Build it in mateclaw-browser-bridge first (pnpm build / build-exe.mjs) to bundle the native host.',
    )
  }
  fs.mkdirSync(bridgeResourceDir, { recursive: true })
  const dest = path.join(bridgeResourceDir, 'bridge.exe')
  fs.copyFileSync(bridgeArtifact, dest)
  console.log(`[desktop] copied bridge binary: ${bridgeArtifact} -> ${dest}`)
}

function createRuntime() {
  const jdkHome = findJdkHome()
  const jlink = path.join(jdkHome, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink')
  fs.rmSync(runtimeResourceDir, { recursive: true, force: true })
  fs.mkdirSync(resourcesDir, { recursive: true })

  const modules = [
    'java.base',
    'java.compiler',
    'java.datatransfer',
    'java.desktop',
    'java.instrument',
    'java.logging',
    'java.management',
    'java.naming',
    'java.net.http',
    'java.prefs',
    'java.rmi',
    'java.scripting',
    'java.security.jgss',
    'java.security.sasl',
    'java.sql',
    'java.sql.rowset',
    'java.transaction.xa',
    'java.xml',
    'java.xml.crypto',
    'jdk.charsets',
    'jdk.crypto.ec',
    'jdk.httpserver',
    'jdk.jfr',
    'jdk.localedata',
    'jdk.management',
    'jdk.unsupported',
    'jdk.zipfs',
  ].join(',')

  execFileSync(
    jlink,
    [
      '--add-modules',
      modules,
      // jdk.localedata 默认会被 jlink 裁掉 non-DEFAULT locale,导致干净机上
      // 中文日期/数字/货币格式退化为 ROOT/英文。--include-locales 仅保留 zh/en,
      // 既修复格式又控制 runtime 体积(全量 locale 会显著增大)。
      '--include-locales=zh,en',
      '--strip-debug',
      '--no-header-files',
      '--no-man-pages',
      '--compress=2',
      '--output',
      runtimeResourceDir,
    ],
    { stdio: 'inherit' },
  )
  console.log(`[desktop] created runtime: ${runtimeResourceDir}`)
}

function verifyResources() {
  // 末尾硬校验:三大产物缺任一即 throw,避免 electron-builder 打出残缺安装包。
  const javaw = path.join(runtimeResourceDir, 'bin', 'javaw.exe')
  const bridge = path.join(bridgeResourceDir, 'bridge.exe')

  const serverJars = fs.existsSync(serverResourceDir)
    ? fs
        .readdirSync(serverResourceDir, { withFileTypes: true })
        .filter((entry) => entry.isFile() && entry.name.endsWith('.jar'))
        .map((entry) => path.join(serverResourceDir, entry.name))
    : []

  const missing = []
  if (!fs.existsSync(javaw)) missing.push(javaw)
  if (!serverJars.length) missing.push(path.join(serverResourceDir, '*.jar'))
  if (!fs.existsSync(bridge)) missing.push(bridge)

  if (missing.length) {
    throw new Error(
      `[desktop] resource verification failed; missing required artifact(s):\n  - ${missing.join('\n  - ')}`,
    )
  }
  console.log('[desktop] resource verification passed: runtime/bin/javaw.exe, server/*.jar, bridge/bridge.exe')
}

copyServerJar()
copyBridgeBinary()
createRuntime()
verifyResources()
