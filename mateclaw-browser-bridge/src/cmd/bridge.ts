/**
 * bridge — MateClaw Browser Agent Native Host entrypoint.
 *
 * Bridges the Chrome Extension (via Native Messaging over stdio) to the
 * MateClaw Control Plane (via Bearer-over-WSS).
 * See ../../docs/specs/edge-protocol.md for the wire format.
 *
 * Invocation modes
 * ----------------
 * This binary is the `path` target of the Chrome Native Messaging manifest
 * (com.mateclaw.browser_bridge). A manifest `path` cannot carry arguments, so
 * when Chrome launches the host it does NOT pass a `run` subcommand. Instead it
 * passes the calling extension's origin as argv[0] and, on Windows, a
 * `--parent-window=<HWND>` flag, e.g.:
 *
 *     bridge.exe chrome-extension://<id>/ --parent-window=12345
 *
 * Older/explicit invocation still works:
 *
 *     bridge --version       → print version and exit
 *     bridge run             → run the native host explicitly
 *
 * Mode resolution (decideMode):
 *   1. `--version` / `-version`           → 'version'
 *   2. explicit `run` subcommand          → 'run'
 *   3. launched by a browser (argv has a
 *      chrome-extension:// origin, OR
 *      stdin is a pipe / non-TTY)         → 'run'
 *   4. otherwise                          → 'usage'
 */
import { fileURLToPath } from 'node:url'
import { realpathSync } from 'node:fs'
import { createRequire } from 'node:module'
import { PassThrough, type Readable, type Writable } from 'node:stream'
import { tryLoadConfig, type Config } from '../internal/config/config.js'
import { Client } from '../internal/edge/client.js'
import { Runner, MsgQueue } from '../internal/runner/runner.js'
import { LoopbackServer } from '../internal/ipc/loopback-server.js'
import {
  writeJsonFrame,
  readFrame,
  writeFrame,
  isOversizeFrame,
  parsePingFrame,
  writePongFrame,
} from '../internal/nm/server.js'
import { Kind, make } from '../internal/edgeproto/edgeproto.js'

export const VERSION = '0.1.0'

/**
 * 无 token 时的有界轮询参数(契约2 / 止血核心)。
 *
 * 背景:桌面首启时,扩展 SW 会抢在桌面壳写出 bridge.yaml 之前就尝试连接 native host,
 * 触发 bridge 启动。旧实现"读不到 token 就发一帧 NO_TOKEN 后立即 return 1 自杀",
 * 叠加 SW 的密集自动重连 → bridge 被反复秒拉起又秒退 = spawn 风暴。
 *
 * 改为:无 token 时不立即退出,而是有界轮询重读配置,等桌面壳把 token 写出来即可正常
 * 接续 Client.connect;只有超时仍无 token 才发 {kind:'error',code:'NO_TOKEN'} 退出。
 *
 * 取值理由:
 *   - 上限 60s:覆盖桌面首启"SW 抢跑 → 桌面壳完成 PAT 申领并写 bridge.yaml"的常见窗口
 *     (慢机/慢网下后端起 + PAT 下发可能需要十几秒到几十秒);太短会退回 spawn 风暴,
 *     太长则在"用户确实未配对"时让进程白挂着,60s 是体感等待与无谓挂起的折中。
 *   - 间隔 1s:文件出现的检测延迟上界 1s,足够灵敏;轮询的是本地小文件,1s/次 的 IO 可忽略。
 */
export const NO_TOKEN_POLL_TIMEOUT_MS = 60_000
export const NO_TOKEN_POLL_INTERVAL_MS = 1_000

/** Resolved invocation mode for the bridge process. */
export type Mode = 'version' | 'run' | 'resident' | 'usage'

/**
 * True when launched in【常驻 bridge 锚】模式(连接根治:1 bridge 常驻 + loopback WS server)。
 *
 * 桌面壳(组2,feature-flag MATECLAW_RESIDENT_BRIDGE 默认 off)在开启时以
 * `bridge.exe run --resident` 常驻拉起本进程。此模式下 bridge 不再用 process.stdin 作为帧
 * 来源,改为开 loopback WS server 给扩展连(契约1),并自己直连后端 WSS 长跑。
 *
 * 默认 off:桌面不传 --resident → 走原有 NM 路径,全系统行为与今天完全一致。
 */
export function isResidentArgv(argv: readonly string[]): boolean {
  return argv.includes('--resident')
}

/**
 * True when the process was launched by a browser as a Native Messaging host.
 *
 * Chromium-based browsers (Chrome, Edge, Brave, …) pass the calling
 * extension's origin as the first program argument, of the form
 * `chrome-extension://<id>/`. We match the scheme prefix rather than a specific
 * id so the same detection works regardless of which extension connects.
 */
export function isNativeMessagingArgv(argv: readonly string[]): boolean {
  return argv.some((a) => a.startsWith('chrome-extension://'))
}

/**
 * Decide how to run, from the program arguments and whether stdin is a TTY.
 *
 * `isStdinTTY` is the only ambient input we take, injected for testability:
 *   - When a human runs `bridge` in a terminal, stdin is a TTY → 'usage'.
 *   - When Chrome spawns the host, stdin is a pipe (non-TTY) → 'run', even if
 *     the chrome-extension:// origin is somehow absent (defensive fallback).
 *
 * `--version` always wins so it is observable in any environment, and an
 * explicit `run` always forces the running path.
 */
export function decideMode(argv: readonly string[], isStdinTTY: boolean): Mode {
  if (argv.includes('--version') || argv.includes('-version')) {
    return 'version'
  }
  // 常驻模式优先于普通 run:`run --resident` / 任意带 --resident 的显式调用都走常驻。
  // 它仅由桌面壳(feature-flag 开启时)显式传入,Chrome 拉起原生 host 时绝不会带这个 flag,
  // 因此 NM 路径不受影响(契约4:默认 off 行为不变)。
  if (isResidentArgv(argv)) {
    return 'resident'
  }
  if (argv[0] === 'run') {
    return 'run'
  }
  if (isNativeMessagingArgv(argv) || !isStdinTTY) {
    return 'run'
  }
  return 'usage'
}

/**
 * Run the Native Host pipeline: load config, connect to the Control Plane,
 * and pump frames between Chrome (stdio) and the edge WebSocket.
 *
 * Returns 0 on clean shutdown, non-zero on fatal error. Never calls
 * process.exit() itself so callers/tests can decide what to do with the code.
 */
export async function runBridge(): Promise<number> {
  // 无 token 时不再立即自杀,而是有界轮询等待桌面壳写出 token(契约2 / 止血核心)。
  // 轮询期间已经开始接收并应答扩展的 ping(保活),即便还没拿到 token —— 这能在配对
  // 完成前就让扩展 SW 维持续期、避免它把 bridge 当成"刚启动就崩"而疯狂重拉。
  const cfg = await waitForToken()

  if (!cfg) {
    // 轮询超时仍无 token:走原来的"发一帧结构化 error 再退出"路径(契约2)。
    // 这一帧是合法 EdgeMessage(v:1, kind:'error'),扩展 parseEdgeMessage 才会接受;
    // 扩展(组A)收到 retryable===false 时应停止 1s 起步的密集自动重连、emit
    // 'unpaired'/'no_token' 状态、改为稀疏探测(如 30s/次)。
    process.stderr.write(
      'bridge run: MATECLAW_BRIDGE_AUTH_TOKEN or bridge.yaml auth_token is required ' +
        `(waited ${Math.round(NO_TOKEN_POLL_TIMEOUT_MS / 1000)}s)\n`,
    )

    if (isNativeMessagingArgv(process.argv) || process.stdin.isTTY !== true) {
      try {
        await writeJsonFrame(
          process.stdout,
          make({
            kind: Kind.Error,
            payload: {
              code: 'NO_TOKEN',
              message:
                'Native host is not paired: no auth token configured (set MATECLAW_BRIDGE_AUTH_TOKEN or bridge.yaml auth_token).',
              retryable: false,
            },
          }),
        )
      } catch {
        // stdout may already be closed if the browser tore the port down first;
        // the stderr line above is still the fallback signal.
      }
    }

    return 1
  }

  const client = new Client({
    url: cfg.controlPlaneUrl,
    authToken: cfg.authToken,
    agentVersion: cfg.agentVersion,
    heartbeatIntervalMs: cfg.heartbeatIntervalMs,
  })

  // ping/pong 拦截:在 stdin 与 Runner 之间插一层"应用层心跳过滤器"。
  // 扩展(组A)经 NM 发来的 {kind:'ping'} 在这里被就地应答 {kind:'pong'} 回 stdout,
  // 【不】转发给 Runner → 后端 WSS(契约1)。其余业务帧原样喂给 Runner。
  // 这样既不改动 runner.ts(不在本组允许文件),又满足"bridge.ts 分发"的拦截点。
  const filteredStdin = new PassThrough()
  const pingDone = pumpStdinWithPingFilter(process.stdin, process.stdout, filteredStdin)
  // 不让过滤泵的异常变成 unhandledRejection(stdin 异常时它会 end 掉 filteredStdin,
  // Runner 随后看到 EOF 正常收尾);这里仅吞掉 reject。
  void pingDone.catch(() => {})

  const runner = new Runner({
    client,
    stdin: filteredStdin,
    stdout: process.stdout,
  })

  // Wire SIGINT / SIGTERM to a graceful abort
  const ac = new AbortController()
  const handleSignal = (): void => {
    if (!ac.signal.aborted) ac.abort()
  }
  process.once('SIGINT', handleSignal)
  process.once('SIGTERM', handleSignal)

  try {
    await runner.run(ac.signal)
    return 0
  } catch (err) {
    process.stderr.write(`bridge run: fatal — ${String(err)}\n`)
    return 1
  } finally {
    process.off('SIGINT', handleSignal)
    process.off('SIGTERM', handleSignal)
  }
}

/**
 * 用给定 cfg + authToken 构造一个 Client。bridge.ts 拥有 cfg(controlPlaneUrl 等),
 * 故 token 长跑重读(契约5)的 Client 重建由这里提供给 Runner.rebuildClient。
 */
function makeClient(
  cfg: Config,
  authToken: string,
  extensionAttached?: () => boolean,
  extensionVersion?: () => string | null,
): Client {
  return new Client({
    url: cfg.controlPlaneUrl,
    authToken,
    agentVersion: cfg.agentVersion,
    heartbeatIntervalMs: cfg.heartbeatIntervalMs,
    // 常驻模式:把"loopback 上是否挂着扩展 + 扩展版本"经 heartbeat 上报后端
    // (契约:extension_attached / extension_version)。不传(NM/直连)时 Client 默认 ()=>true / ()=>null。
    extensionAttached,
    extensionVersion,
  })
}

/**
 * 常驻模式入口(连接根治:1 bridge 常驻 + loopback WS server + 去 stdin-EOF=死)。
 *
 * 与 {@link runBridge}(NM 模式)的根本区别:
 *   - 帧来源不是 process.stdin,而是 loopback WS server 收到的扩展帧(契约1)。
 *   - 扩展⇄loopback 断开只 detach 该 IPC 连接,绝不触发后端段重连(契约5)。
 *   - bridge↔后端 WSS 由 Runner 独立长跑(forever-reconnect 不变)。
 *   - 后端段健康度经 loopback 持续上报扩展(契约3)。
 *   - 每次重连后端前重读最新 token、遇 401 用新 token 重建 Client(契约5)。
 *
 * 单实例锁:loopback listen 命中 EADDRINUSE 即判定"已有常驻实例",干净退出(返回 0)。
 *
 * 仅在桌面壳(feature-flag MATECLAW_RESIDENT_BRIDGE 开启)以 `run --resident` 拉起时进入。
 */
export async function runResident(): Promise<number> {
  // 与 NM 模式一致:无 token 时有界轮询等桌面壳写出 bridge.yaml(契约2),不立即自杀。
  const cfg = await waitForToken()
  if (!cfg) {
    process.stderr.write(
      'bridge run --resident: auth token not available after waiting; exiting. ' +
        '(set MATECLAW_BRIDGE_AUTH_TOKEN or bridge.yaml auth_token)\n',
    )
    return 1
  }

  // 扩展帧来源队列 —— Runner 与 LoopbackServer 共享同一实例(契约2)。
  const inboundQueue = new MsgQueue()

  const loopback = new LoopbackServer({ inboundQueue })

  // 先抢端口(单实例锁)。EADDRINUSE → 已有常驻实例在跑,本进程让位、干净退出。
  const started = await loopback.start()
  if (!started.ok) {
    if (started.reason === 'EADDRINUSE') {
      process.stderr.write(
        'bridge run --resident: loopback port already in use — another resident bridge owns it; exiting cleanly.\n',
      )
      return 0 // 不是错误:既有实例已在服务扩展,本进程退出即可。
    }
    process.stderr.write(`bridge run --resident: failed to start loopback server — ${String(started.error)}\n`)
    return 1
  }

  // 初始 Client(用当前 token);后续 token 轮换由 Runner 经 rebuildClient 重建。
  // 注入"扩展在场"查询器 —— 每次 heartbeat 上报后端,使 UI 显示真实连接状态。
  const client = makeClient(
    cfg,
    cfg.authToken,
    () => loopback.isExtensionAttached(),
    () => loopback.extensionVersion(),
  )

  const runner = new Runner({
    client,
    // IPC 模式:帧来源/汇全部走 loopback,process.stdin/stdout 完全不参与(去 stdin-EOF=死)。
    ipc: {
      inboundQueue,
      onInbound: loopback.onInbound,
      onUpstreamState: loopback.onUpstreamState,
    },
    initialAuthToken: cfg.authToken,
    // token 长跑重读(契约5):每次(重)连后端前重读 bridge.yaml 的最新 auth_token。
    reloadAuthToken: async () => {
      const res = await tryLoadConfig()
      return res.status === 'ready' && res.config ? res.config.authToken : null
    },
    // 用新 token 重建 Client。复用当前 cfg 的 url/version/心跳;仅换凭据。
    rebuildClient: (authToken: string) =>
      makeClient(cfg, authToken, () => loopback.isExtensionAttached(), () => loopback.extensionVersion()),
  })

  // 脆握手:扩展 attach / ext_hello 时,让 Runner 用当前 Client 立即补发一帧 heartbeat 即时上报后端,
  // 使"已连接 + 扩展版本"~1s 内到达 UI,而非等 ~10s 心跳周期(实现"点连接→扩展回执→才算连上")。
  loopback.setPresenceListener(() => runner.notifyExtensionPresence())

  const ac = new AbortController()
  const handleSignal = (): void => {
    if (!ac.signal.aborted) ac.abort()
  }
  process.once('SIGINT', handleSignal)
  process.once('SIGTERM', handleSignal)

  try {
    await runner.run(ac.signal)
    return 0
  } catch (err) {
    process.stderr.write(`bridge run --resident: fatal — ${String(err)}\n`)
    return 1
  } finally {
    process.off('SIGINT', handleSignal)
    process.off('SIGTERM', handleSignal)
    await loopback.close().catch(() => {})
  }
}

/**
 * 有界轮询等待 token(契约2 / 止血核心)。
 *
 * 反复 {@link tryLoadConfig} 直到:
 *   - 拿到非空 authToken              → 返回该 Config,调用方接续 Client.connect。
 *   - 距首次尝试已超过 timeoutMs 仍无 → 返回 null,调用方发 NO_TOKEN error 后退出。
 *   - 读到硬错误(YAML 解析/IO 失败) → 立即返回 null(重读同一坏文件不会变好),
 *     避免在已知坏配置上白白挂满整个超时窗口。
 *
 * 这取代了旧的"无 token 即 return 1 自杀"——桌面首启 SW 抢跑只会让 bridge 安静等
 * token 写出,而非反复秒退被 SW 拉起(spawn 风暴)。注入 timeoutMs/intervalMs/now/sleep
 * 仅为可测试性;生产用默认常量。
 */
export async function waitForToken(opts?: {
  timeoutMs?: number
  intervalMs?: number
  now?: () => number
  sleep?: (ms: number) => Promise<void>
}): Promise<Config | null> {
  const timeoutMs = opts?.timeoutMs ?? NO_TOKEN_POLL_TIMEOUT_MS
  const intervalMs = opts?.intervalMs ?? NO_TOKEN_POLL_INTERVAL_MS
  const now = opts?.now ?? Date.now
  const sleep = opts?.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))

  const deadline = now() + timeoutMs

  for (;;) {
    const res = await tryLoadConfig()
    if (res.status === 'ready' && res.config) {
      return res.config
    }
    if (res.status === 'error') {
      // 硬错误:坏 YAML / 权限问题。重试无意义,提早收手让调用方发 NO_TOKEN。
      process.stderr.write(
        `bridge run: config read error, not retrying — ${String(res.error)}\n`,
      )
      return null
    }
    // status === 'no-token':桌面壳尚未写出 token,继续等待——除非已到截止时间。
    if (now() >= deadline) {
      return null
    }
    await sleep(intervalMs)
  }
}

/**
 * stdin → Runner 之间的"应用层心跳过滤器"(契约1)。
 *
 * 从真实 stdin 逐帧读 NM 帧:
 *   - {kind:'ping'}  → 就地经 stdout 回 {kind:'pong', ts:<原 ts>},【不】下发给 Runner
 *                      (因此也不会被转发到后端 WSS);ping/pong 只服务扩展⇄bridge 段保活。
 *   - 其余业务帧      → 原样(重新加 4 字节长度前缀)写入 `out`,交给 Runner 正常处理。
 *   - 超大帧 sentinel → 与 Runner 旧行为一致:记一条 stderr 诊断后丢弃,不杀连接、保持对齐。
 *   - EOF            → end 掉 `out`,Runner 看到 EOF 干净收尾。
 *
 * 之所以放在这里而非 runner.ts:runner.ts 不在本组允许文件,且契约明确拦截点为
 * "nm/server.ts 读帧 / bridge.ts 分发"。本函数仅消费真实 stdin 一次(单一消费者),
 * Runner 改读本函数产出的 `out` 流,因此不存在 stdin 双读竞态。
 */
export async function pumpStdinWithPingFilter(
  stdin: Readable,
  stdout: Writable,
  out: PassThrough,
): Promise<void> {
  try {
    for (;;) {
      const frame = await readFrame(stdin)
      if (frame === null) {
        // stdin EOF —— 告诉 Runner 没有更多帧了。
        out.end()
        return
      }

      if (isOversizeFrame(frame)) {
        process.stderr.write(
          `[bridge] dropped oversize NM frame: declared=${frame.declaredBytes} max=${frame.maxBytes}\n`,
        )
        continue
      }

      // frame 是普通帧 body(Buffer)。先看是不是 ping。
      const ping = parsePingFrame(frame)
      if (ping) {
        // 就地回 pong,原样带回 ts;不下发给 Runner(契约1:不污染 edge 业务帧、不上 WSS)。
        try {
          await writePongFrame(stdout, ping)
        } catch {
          // stdout 可能已被浏览器关闭;静默——保活失败由扩展侧的 watchdog 兜底。
        }
        continue
      }

      // 普通业务帧:重新封帧后转交 Runner(Runner 内部会再 parse + 入队 + 上 WSS)。
      try {
        await writeFrame(out, frame)
      } catch {
        // 下游 PassThrough 异常(极少见)——结束转发,让 Runner 看到 EOF 收尾。
        out.end()
        return
      }
    }
  } catch (err) {
    // readFrame 仅在"帧中途 EOF"(真正的流损坏)时抛错。结束下游让 Runner 干净收尾。
    process.stderr.write(`[bridge] stdin ping-filter stopped: ${String(err)}\n`)
    out.end()
  }
}

/**
 * Top-level dispatcher. Resolves the mode, performs the side effect, and
 * returns the process exit code. Kept free of process.exit() so it stays unit
 * testable; the entrypoint shim below translates the code into an exit.
 */
export async function main(
  argv: readonly string[],
  isStdinTTY: boolean,
): Promise<number> {
  switch (decideMode(argv, isStdinTTY)) {
    case 'version':
      process.stdout.write(`mateclaw-browser-bridge ${VERSION}\n`)
      return 0
    case 'resident':
      return runResident()
    case 'run':
      return runBridge()
    case 'usage':
      process.stderr.write(
        'bridge: no command (try --version, run, or run --resident)\n',
      )
      return 1
  }
}

/** True when running inside a Node Single-Executable Application (bridge.exe). */
function isSeaBinary(): boolean {
  try {
    // `node:sea` exists on Node 20+. In a normal `node script.js` run isSea()
    // returns false; inside the packaged exe it returns true.
    const sea = createRequire(import.meta.url)('node:sea') as { isSea(): boolean }
    return sea.isSea()
  } catch {
    return false
  }
}

/**
 * True when this module is the program entrypoint (i.e. executed directly via
 * `node dist/cmd/bridge.js` or the packaged bridge.exe), as opposed to being
 * imported by a test or another module. Guarding on this prevents the CLI from
 * running — and calling process.exit() — when the unit tests import it.
 *
 * In a Single-Executable Application there is no argv[1] file on disk, so we
 * detect that first and always run. Otherwise we compare the realpath of the
 * file that started the process (process.argv[1]) with this module's own path.
 */
function isEntrypoint(): boolean {
  if (isSeaBinary()) return true
  try {
    const self = fileURLToPath(import.meta.url)
    const invoked = process.argv[1]
    if (!invoked) return true
    return realpathSync(self) === realpathSync(invoked)
  } catch {
    // Unusual host with no resolvable module URL → assume we are the program.
    return true
  }
}

if (isEntrypoint()) {
  // stdin.isTTY is `true` only for an interactive terminal; it is `undefined`
  // for a pipe (Chrome) — coerce to a strict boolean.
  const isStdinTTY = process.stdin.isTTY === true
  main(process.argv.slice(2), isStdinTTY)
    .then((code) => process.exit(code))
    .catch((err) => {
      process.stderr.write(`bridge: unexpected error — ${String(err)}\n`)
      process.exit(1)
    })
}
