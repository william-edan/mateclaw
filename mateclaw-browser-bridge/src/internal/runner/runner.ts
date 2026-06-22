/**
 * Runner — orchestrates the Native Host pipeline.
 *
 * Responsibilities:
 *   - Connect the Client to the Control Plane.
 *   - Pump stdin frames (Chrome NM) → parse JSON → stamp session_id → send via Client.
 *   - Pump inbound messages from Client → serialise JSON → write NM frames to stdout.
 *   - Run the Client heartbeat loop concurrently.
 *   - On any I/O or heartbeat error, sleep with exponential backoff + ±20% jitter,
 *     then reconnect — forever by default (slow-network resilience P0#5). A flaky
 *     or slow link must NOT make the whole Native Host exit; only an abort signal
 *     (intentional stop) ends the loop cleanly. An optional maxAttempts cap exists
 *     purely for tests; 0/undefined means "retry indefinitely".
 *
 * Security invariant (Codex P0-1):
 *   The Native Host is the SOLE owner of session_id. Whatever the Extension sends
 *   in session_id is unconditionally overwritten with the server-issued id.
 *
 * Single-reader invariant:
 *   Runner consumes client.inbound() (AsyncIterable). It does NOT attach any
 *   ws.on('message', ...) handler directly.
 *
 * Promise.race policy:
 *   Promise.race is used ONLY at the lifecycle level (runOnce) to detect the first
 *   failing pump. It is NOT used on the action message stream.
 *
 * Reconnect design:
 *   stdin is a persistent pipe (Chrome Native Messaging) — it stays open across
 *   reconnects. A single long-running stdinReader loop decodes NM frames from
 *   stdin into a local AsyncQueue<Message>. On each reconnect, pumpQueueToEdge
 *   drains that queue to the edge. This avoids the double-consumer race that
 *   arises from restarting readFrame(stdin) on each attempt.
 *
 * IPC mode (连接根治:常驻 bridge + loopback WS):
 *   常驻模式下帧来源不再是 process.stdin,而是一个外部 {@link MsgQueue} —— 由
 *   loopback-server(扩展⇄bridge 这一段)把扩展发来的 EdgeMessage 入队。Client 经
 *   inbound() 收到的后端消息则交给一个外部回调(sink)转发回当前已连扩展。关键不变量:
 *   "扩展⇄loopback 这一段断开"绝不能冒泡触发"bridge↔后端 WSS"重连/重发 HELLO(契约5,
 *   否则后端 4409 单活替换、session 更替)。两个连接的生命周期在 bridge 内彻底解耦:
 *     - detach 当前 IPC 连接 = 仅清空 sink 回调,【不】close 队列、【不】停 Client、
 *       【不】触发后端重连。Client 的 forever-reconnect 循环独立长跑。
 *   构造方式向后兼容:不传 ipc 选项时,Runner 行为与今天(stdin/stdout NM 模式)完全一致。
 */
import type { Readable, Writable } from 'node:stream'
import { Client, AuthError } from '../edge/client.js'
import { readFrame, writeFrame, isOversizeFrame } from '../nm/server.js'
import { parse, type Message } from '../edgeproto/edgeproto.js'

// ── Internal async queue ─────────────────────────────────────────────────────

/**
 * 单消费者的有序异步消息队列。NM 模式下由 stdin reader 入队,IPC(常驻)模式下由
 * loopback-server 入队。导出以便 loopback-server(同组允许文件)直接持有同一队列实例,
 * 把扩展帧 enqueue 进来 —— 这正是契约2要求的"把 inbound EdgeMessage 帧 enqueue 进
 * Runner 的 MsgQueue"。
 */
export class MsgQueue implements AsyncIterable<Message> {
  readonly #buf: Message[] = []
  readonly #waiters: Array<(v: IteratorResult<Message>) => void> = []
  #done = false

  enqueue(msg: Message): void {
    if (this.#done) return
    if (this.#waiters.length > 0) {
      this.#waiters.shift()!({ value: msg, done: false })
      return
    }
    this.#buf.push(msg)
  }

  close(): void {
    this.#done = true
    for (const w of this.#waiters.splice(0)) {
      w({ value: undefined as unknown as Message, done: true })
    }
  }

  [Symbol.asyncIterator](): AsyncIterator<Message> {
    return {
      next: (): Promise<IteratorResult<Message>> => {
        if (this.#buf.length > 0) {
          return Promise.resolve({ value: this.#buf.shift()!, done: false })
        }
        if (this.#done) {
          return Promise.resolve({ value: undefined as unknown as Message, done: true })
        }
        return new Promise((resolve) => this.#waiters.push(resolve))
      },
    }
  }
}

// ── IPC delegate (常驻 loopback 模式)───────────────────────────────────────────

/**
 * IPC 模式下 Runner 与 loopback-server 之间的契约对象(连接根治 / 契约1、3、5)。
 *
 * - inboundQueue:loopback-server 把"扩展⇄bridge 段"收到的 EdgeMessage 入队;Runner 从
 *   这里取帧、盖 session_id、上后端 WSS。它的生命周期由 loopback-server 管理(进程级),
 *   Runner【绝不】close 它 —— 单个扩展连接断开不应让常驻 Runner 的帧来源枯竭(契约5)。
 * - onInbound:Client 经 inbound() 收到的后端消息,Runner 调此回调交给 loopback-server
 *   转发回当前已连扩展。无扩展连接时由 loopback-server 自行丢弃,Runner 不感知。
 * - onUpstreamState(可选):后端 WSS/session 健康度变化时 Runner 回调,loopback-server
 *   据此向扩展持续上报 {kind:'upstream', state:'up'|'down'}(契约3,端到端 isConnected
 *   的关键 —— 扩展不能只看本地 socket OPEN,否则假阳性)。
 */
export interface IpcDelegate {
  /** 扩展帧来源队列(由 loopback-server 入队;Runner 只消费,不关闭)。 */
  inboundQueue: MsgQueue
  /** 后端→扩展:把一条后端下行 Message 转发回当前已连扩展。 */
  onInbound: (msg: Message) => void
  /** 后端 WSS/session 健康度上报(契约3)。可选;不传则不上报。 */
  onUpstreamState?: (state: 'up' | 'down') => void
}

// ── Runner options ────────────────────────────────────────────────────────────

export interface RunnerOptions {
  client: Client
  /**
   * NM(Native Messaging)模式的帧来源/汇:Chrome 经 stdio 喂帧、bridge 回 stdout。
   * 与 {@link RunnerOptions.ipc} 互斥 —— 传 ipc 时 stdin/stdout 不被使用,可省略。
   */
  stdin?: Readable
  stdout?: Writable
  /**
   * IPC(常驻 loopback)模式:帧来源改为外部 {@link MsgQueue},后端下行经回调转发回
   * 扩展;process.stdin/stdout 完全不参与(去 stdin-EOF=死,契约5)。传此项即进入常驻模式。
   */
  ipc?: IpcDelegate
  /** Initial backoff in ms (default 1000). Doubles per attempt. */
  backoffBase?: number
  /** Maximum backoff cap in ms (default 30000). */
  backoffMax?: number
  /**
   * Maximum consecutive reconnect attempts before throwing. Defaults to
   * unlimited (0 / undefined) — slow-network jitter must not make the Native
   * Host give up and exit. Set a finite cap only for tests.
   */
  maxAttempts?: number
  /**
   * IPC 模式下重连后端前重读 token 的钩子(契约5 / token 长跑重读)。默认用
   * {@link tryLoadConfig} 读 ~/.mateclaw/bridge.yaml 的最新 auth_token。注入仅为可测试性。
   * 返回非空且与当前 client 持有的 token 不同时,Runner 调 {@link RunnerOptions.rebuildClient}
   * 用新 token 重建 Client(client.ts 不在本组允许文件,故重建委托给上层)。
   */
  reloadAuthToken?: () => Promise<string | null>
  /**
   * 用给定 authToken 重建一个全新 Client(契约5 / token 长跑重读)。由 bridge.ts 提供
   * (它本就拥有 Client 构造所需的 cfg)。在两种时机被调用:
   *   1) 重连前 reloadAuthToken 读到与当前不同的新 token;
   *   2) connect() 抛 AuthError(401)—— 旧 token 已失效,带新 token 重试。
   * 不传则禁用 token 长跑重读,沿用初始 Client(NM 模式与旧行为)。
   */
  rebuildClient?: (authToken: string) => Client
  /**
   * 初始 Client 当前持有的 authToken,供 reloadAuthToken 比较"是否变化"用。仅在配合
   * reloadAuthToken + rebuildClient 时有意义;不影响 NM 模式。
   */
  initialAuthToken?: string
}

// ── Runner ───────────────────────────────────────────────────────────────────

export class Runner {
  // 当前 Client。NM 模式恒为构造时的 client;IPC 模式下可能被 token 长跑重读用新 token 重建。
  #client: Client
  readonly #stdin: Readable | null
  readonly #stdout: Writable | null
  readonly #ipc: IpcDelegate | null
  readonly #backoffBase: number
  readonly #backoffMax: number
  readonly #maxAttempts: number
  readonly #reloadAuthToken: (() => Promise<string | null>) | null
  readonly #rebuildClient: ((authToken: string) => Client) | null
  /** Runner 已知的当前 authToken,用于判断 reload 出的 token 是否变化(契约5)。 */
  #currentAuthToken: string

  constructor(opts: RunnerOptions) {
    this.#client = opts.client
    this.#ipc = opts.ipc ?? null
    // IPC 模式不使用 stdin/stdout;NM 模式必须二者俱全。
    this.#stdin = opts.stdin ?? null
    this.#stdout = opts.stdout ?? null
    if (!this.#ipc && (!this.#stdin || !this.#stdout)) {
      throw new Error('Runner: NM 模式需要 stdin 与 stdout;IPC 模式请改传 opts.ipc')
    }
    this.#backoffBase = opts.backoffBase ?? 1000
    this.#backoffMax = opts.backoffMax ?? 30_000
    // 0 = retry forever (default). A finite cap is only used by tests.
    this.#maxAttempts = opts.maxAttempts ?? 0
    this.#reloadAuthToken = opts.reloadAuthToken ?? null
    this.#rebuildClient = opts.rebuildClient ?? null
    this.#currentAuthToken = opts.initialAuthToken ?? ''
  }

  /**
   * Outer reconnect loop. Runs until aborted (clean stop). By default it never
   * gives up on transient failures — it keeps reconnecting with capped
   * exponential backoff so slow/flaky networks self-heal instead of killing the
   * Native Host. A finite maxAttempts cap (tests only) still throws after N
   * consecutive failures.
   *
   * A single stdinReader loop reads NM frames into a MsgQueue and runs for
   * the lifetime of the outer loop. The reconnect inner loop drains the queue
   * to the edge on each attempt.
   */
  async run(signal?: AbortSignal): Promise<void> {
    // 帧来源:
    //   - IPC 模式:用 loopback-server 提供的外部队列(契约2)。Runner 绝不 close 它,也不
    //     起 stdin reader —— 单个扩展连接断开不能让常驻 Runner 的帧来源枯竭(契约5)。
    //   - NM 模式:维持原行为:本地建队列 + 起 stdin reader,退出时 close 队列。
    const ownsStdin = this.#ipc === null
    const frameQueue = this.#ipc ? this.#ipc.inboundQueue : new MsgQueue()
    // Single iterator — shared across all runOnce attempts so we don't
    // re-read items that were already consumed before a disconnect.
    const frameIter = frameQueue[Symbol.asyncIterator]()

    // NM 模式才起持久 stdin reader(读到 EOF/abort 结束);IPC 模式队列由外部喂。
    const stdinDonePromise = ownsStdin
      ? this.#runStdinReader(frameQueue, signal)
      : Promise.resolve()

    let attempt = 0

    while (true) {
      if (signal?.aborted) break

      // token 长跑重读(契约5):每次(重)连后端前重读最新 token,变化则用新 token 重建
      // Client。注意这只换"bridge↔后端"这条连接的凭据,与"扩展⇄loopback"段完全无关。
      await this.#maybeRefreshClientToken()

      try {
        await this.#runOnce(frameIter, signal)
        // Clean exit (signal aborted inside runOnce) — stop.
        break
      } catch (err) {
        if (signal?.aborted) break

        // AuthError(后端 401):旧 token 已失效。若有 rebuildClient,带最新 token 重建后
        // 不计入退避节流,立刻重试(契约5);否则按普通失败退避。
        if (err instanceof AuthError && this.#rebuildClient && this.#reloadAuthToken) {
          const refreshed = await this.#maybeRefreshClientToken(true)
          if (refreshed) {
            // 用新 token,attempt 不自增、不退避 —— 这不是网络抖动,是凭据轮换。
            continue
          }
        }

        attempt++
        // maxAttempts <= 0 → retry forever (default). A finite cap (tests only)
        // throws after N consecutive failures.
        if (this.#maxAttempts > 0 && attempt >= this.#maxAttempts) {
          if (ownsStdin) frameQueue.close()
          await stdinDonePromise.catch(() => {})
          throw new Error(
            `runner: ${attempt} consecutive failures — giving up. Last error: ${String(err)}`,
          )
        }

        // Exponential backoff with ±20% jitter
        const base = Math.min(this.#backoffBase * Math.pow(2, attempt - 1), this.#backoffMax)
        const jitter = base * 0.2 * (Math.random() * 2 - 1) // ±20%
        const delay = Math.max(0, base + jitter)

        await sleep(delay, signal)
      }
    }

    // 仅 NM 模式才 close 自有队列;IPC 模式队列属于 loopback-server,这里绝不 close(契约5)。
    if (ownsStdin) frameQueue.close()
    await stdinDonePromise.catch(() => {})
  }

  /**
   * token 长跑重读(契约5)。重读最新 authToken;若与当前不同且具备 rebuildClient,则用
   * 新 token 重建 Client 并替换 #client。返回 true 表示发生了重建。
   *
   * @param force 为 true 时即便 reload 出的 token 与当前相同也强制重建(用于 401 后:旧 token
   *              可能"字面相同但服务端已撤销",但更常见是桌面壳已写出新 token,这里宽松重建)。
   */
  async #maybeRefreshClientToken(force = false): Promise<boolean> {
    if (!this.#reloadAuthToken || !this.#rebuildClient) return false
    let next: string | null
    try {
      next = await this.#reloadAuthToken()
    } catch {
      // 读配置失败不应中断重连;沿用当前 Client。
      return false
    }
    if (!next || next.trim() === '') return false
    if (!force && next === this.#currentAuthToken) return false
    // token 变了(或强制):用新 token 重建 Client。旧 Client 由 #runOnce 的 finally 收尾。
    this.#currentAuthToken = next
    this.#client = this.#rebuildClient(next)
    return true
  }

  /**
   * Persistent stdin reader — decodes NM frames and enqueues parsed Messages.
   * Runs for the full session lifetime (stdin stays open across reconnects).
   * 仅 NM 模式调用(run() 保证此时 #stdin 非空)。
   */
  async #runStdinReader(queue: MsgQueue, signal?: AbortSignal): Promise<void> {
    const stdin = this.#stdin
    if (!stdin) return // IPC 模式不应走到这里;防御性返回。
    // Abort sentinel — resolves when outer signal fires
    const abortedP = new Promise<null>((resolve) => {
      if (signal?.aborted) { resolve(null); return }
      signal?.addEventListener('abort', () => resolve(null), { once: true })
    })

    for (;;) {
      if (signal?.aborted) return

      const frame = await Promise.race([readFrame(stdin), abortedP])
      if (frame === null) { queue.close(); return } // EOF or abort

      // 超大帧(declaredBytes > MAX_FRAME_BYTES,通常是超大截图):body 已被 readFrame
      // drain 丢弃以保持后续帧对齐、连接不断。原帧的 msg_id/in_reply_to 随 body 丢失,
      // 无法精确回 RESULT_TOO_LARGE(后端靠该 action 的 deadline 超时收尾);这里记一条
      // stderr 诊断(stdout 是 NM 协议流、不能污染),不入队、不杀连接。
      if (isOversizeFrame(frame)) {
        process.stderr.write(
          `[bridge] dropped oversize NM frame: declared=${frame.declaredBytes} max=${frame.maxBytes}\n`,
        )
        continue
      }

      const msg = parse(frame.toString())
      if (msg) queue.enqueue(msg)
    }
  }

  /**
   * Single connection attempt.
   * Connects, then races the three concurrent pumps.
   * Returns normally on clean abort; throws on any pump error (triggers reconnect).
   */
  async #runOnce(stdinIter: AsyncIterator<Message>, signal?: AbortSignal): Promise<void> {
    if (signal?.aborted) return

    // Connect (may throw) —— connect() 内含 hello/hello.ack 往返,resolve 即代表
    // "bridge↔后端 WSS/session 已健康"。connect() 抛错时不上报 up,保持 down(契约3)。
    await this.#client.connect()

    // 后端段健康:上报 upstream up(契约3)。仅 IPC 模式有 onUpstreamState;NM 模式无此回调。
    this.#reportUpstream('up')

    // Create a per-connection AbortController so we can cancel pumps
    const innerAc = new AbortController()
    const cleanup = (): void => innerAc.abort()

    // If the outer signal fires, propagate to inner
    signal?.addEventListener('abort', cleanup, { once: true })

    try {
      // Race all three loops — the first to reject wins.
      // clientRun rejects on HeartbeatTimeoutError; pumps reject on I/O errors.
      await Promise.race([
        this.#pumpQueueToEdge(stdinIter, innerAc.signal).finally(() => innerAc.abort()),
        this.#pumpInbound(innerAc.signal).finally(() => innerAc.abort()),
        this.#client.run(innerAc.signal).finally(() => innerAc.abort()),
      ])
    } finally {
      signal?.removeEventListener('abort', cleanup)
      innerAc.abort() // ensure all loops stop
      // 后端段断开:上报 upstream down(契约3)。无论是被 abort(主动停)还是 race 抛错(掉线),
      // 此刻 bridge↔后端 都已不健康,扩展据此把 isConnected 置 false,杜绝"本地 socket OPEN
      // 但后端早断"的假阳性。
      this.#reportUpstream('down')
      await this.#client.close().catch(() => {})
      // Reset client state so it can reconnect on the next attempt
      this.#client.reset()
    }
  }

  /** 向 loopback-server 上报后端段健康度(契约3)。仅 IPC 模式有效;NM 模式为 no-op。 */
  #reportUpstream(state: 'up' | 'down'): void {
    try {
      this.#ipc?.onUpstreamState?.(state)
    } catch {
      // 上报失败(扩展已断等)不应影响后端连接生命周期 —— 静默吞掉(契约5:两段解耦)。
    }
  }

  /**
   * Pump: shared MsgQueue iterator → stamp session_id → client.send().
   * The iterator is shared across reconnect attempts so no frames are lost.
   * Returns on signal abort. Throws on send failure.
   */
  async #pumpQueueToEdge(iter: AsyncIterator<Message>, signal: AbortSignal): Promise<void> {
    // Sentinel that resolves (to a done result) when signal is aborted
    const abortedP = new Promise<IteratorResult<Message>>((resolve) => {
      if (signal.aborted) {
        resolve({ value: undefined as unknown as Message, done: true })
        return
      }
      signal.addEventListener('abort', () => {
        resolve({ value: undefined as unknown as Message, done: true })
      }, { once: true })
    })

    for (;;) {
      if (signal.aborted) return

      const result = await Promise.race([iter.next(), abortedP])
      if (result.done) return // abort sentinel fired, or queue closed

      const msg = result.value
      // OVERRIDE — do NOT trust whatever the Extension sent.
      msg.session_id = this.#client.sessionId()

      try {
        await this.#client.send(msg)
      } catch {
        if (signal.aborted) return
        throw new Error('runner: failed to send message to edge')
      }
    }
  }

  /**
   * Pump: client.inbound() 后端下行消息 → 下游。
   *   - NM 模式:JSON → NM 帧 → stdout(原行为)。
   *   - IPC 模式:调 ipc.onInbound(msg),由 loopback-server 转发回当前已连扩展。
   * Throws if the iterator exhausts without being aborted — that means the
   * remote end dropped the connection unexpectedly, which should trigger reconnect.
   *
   * 重要:IPC 模式下"扩展已断、没有可转发的目标"绝不抛错(那是扩展⇄loopback 段的事,
   * 与后端段无关)。onInbound 内部自行决定丢弃,Runner 这里只管把后端消息递出去(契约5)。
   */
  async #pumpInbound(signal: AbortSignal): Promise<void> {
    const ipc = this.#ipc
    const stdout = this.#stdout
    for await (const msg of this.#client.inbound()) {
      if (signal.aborted) return
      if (ipc) {
        try {
          ipc.onInbound(msg)
        } catch {
          // 扩展侧转发失败不影响后端连接 —— 吞掉,后端段继续(契约5)。
        }
      } else if (stdout) {
        try {
          await writeFrame(stdout, JSON.stringify(msg))
        } catch {
          if (signal.aborted) return
          throw new Error('runner: failed to write frame to stdout')
        }
      }
    }
    // Iterator exhausted —— 这是后端 WSS 掉线(client.inbound() 在 ws close 时 close 队列)。
    if (signal.aborted) return // clean abort — normal
    throw new Error('runner: inbound channel closed unexpectedly (remote disconnect)')
  }
}

// ── helpers ───────────────────────────────────────────────────────────────────

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise<void>((resolve) => {
    if (signal?.aborted) { resolve(); return }
    const h = setTimeout(resolve, ms)
    signal?.addEventListener('abort', () => { clearTimeout(h); resolve() }, { once: true })
  })
}
