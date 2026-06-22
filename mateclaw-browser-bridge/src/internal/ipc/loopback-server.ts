/**
 * loopback-server — 常驻 bridge 的本地 IPC 端(连接根治:1 bridge 常驻 + loopback WS server)。
 *
 * 背景与目标
 * ----------
 * 旧路:Chrome 经 Native Messaging(stdio)拉起 bridge,bridge 用 process.stdin 作为帧来源。
 * stdin EOF(扩展端 port 断、SW 重启)= bridge 进程死 = bridge↔后端 WSS 一并断 → 后端按
 * 单活策略 4409 替换、session 更替,造成"零 evict"被破坏 + 中途断线重连。
 *
 * 新路(本文件):bridge 由桌面壳常驻拉起(`run --resident`),它:
 *   1) 自己直连后端 WSS(Runner 既有 forever-reconnect 不变),
 *   2) 同时在 ws://127.0.0.1:18077/bridge 开一个【本地】WS server 给扩展连(契约1)。
 * 扩展⇄loopback 这一段断开只 detach 当前 IPC 连接,【绝不】冒泡触发后端段重连/重发 HELLO
 * (契约5,生命周期硬隔离)。两段生命周期在 bridge 内彻底解耦。
 *
 * 帧格式(契约1)
 * --------------
 * 复用现有 EdgeMessage JSON —— 与 direct-WSS 完全一致(扩展 DirectBridgeClient 那套)。WS 文本
 * 帧里就是一个 JSON.stringify 的 EdgeMessage,【不】用 Native Messaging 的 4 字节长度前缀
 * (那是 stdio 专用)。本地不发明新格式。
 *
 * Origin 鉴权(契约2)
 * -------------------
 * 握手阶段校验 Origin 头严格等于固定扩展 origin(chrome-extension://<固定 id>)。Chrome 扩展
 * 发起 WS 连接时浏览器自动带该 Origin,扩展无需额外传 secret。非此 Origin → 403 拒绝。再叠加
 * "仅监听 127.0.0.1"做双重收口(外部网络根本到不了)。
 *
 * upstream 健康上报(契约3)
 * -------------------------
 * bridge 把"它↔后端 WSS/session 是否健康"作为一类控制消息持续推给已连扩展:
 *   {kind:'upstream', state:'up'|'down'}
 * 扩展(组3)收到后经 OFFSCREEN_UPSTREAM_STATE relay 给 SW,SW 据"本地 IPC OPEN ∧ upstream up"
 * 综合判定 isConnected —— 杜绝"只看本地 socket OPEN 就报 connected"的假阳性。新扩展连接接入时
 * 立即补发一次最近已知的 upstream 状态,避免它在下一次状态翻转前一直"不知道后端死活"。
 *
 * 单实例锁(契约2 收尾)
 * --------------------
 * listen 命中 EADDRINUSE 即判定"已有一个常驻 bridge 实例占着 18077",start() 以
 * {ok:false, reason:'EADDRINUSE'} 返回,调用方据此干净退出 —— 不与既有常驻实例抢端口。
 */
import { WebSocketServer, WebSocket } from 'ws'
import type { IncomingMessage } from 'node:http'
import type { MsgQueue } from '../runner/runner.js'
import { parse, make, Kind, type Message } from '../edgeproto/edgeproto.js'
import { isPingOrPongFrame } from '../nm/server.js'
import {
  LOOPBACK_PORT,
  LOOPBACK_HOST,
  LOOPBACK_PATH,
  LOOPBACK_ALLOWED_ORIGIN,
} from '../config/config.js'

/** loopback 控制消息:upstream 健康度(契约3)。沿用 EdgeMessage 信封,kind 走 Unknown 通道
 *  时仍可被扩展 parseEdgeMessage 接受 —— payload.state 携带 'up'|'down'。 */
export const UPSTREAM_KIND = 'upstream'

export interface LoopbackServerOptions {
  /** 扩展帧来源队列。由 Runner 持有同一实例(IpcDelegate.inboundQueue),loopback 入队、Runner 消费。 */
  inboundQueue: MsgQueue
  /** 监听端口(默认 {@link LOOPBACK_PORT}=18077)。 */
  port?: number
  /** 监听主机(默认 {@link LOOPBACK_HOST}=127.0.0.1,仅回环)。 */
  host?: string
  /** WS 路径(默认 {@link LOOPBACK_PATH}=/bridge)。 */
  path?: string
  /** 允许的扩展 Origin(默认固定扩展 origin)。注入仅为可测试性。 */
  allowedOrigin?: string
  /** 诊断日志钩子(默认写 stderr;stdout 不能污染——常驻模式下 stdout 不是协议流,但保持一致)。 */
  log?: (line: string) => void
}

/** start() 的结果:成功监听,或端口被占(已有常驻实例)。 */
export type StartResult =
  | { ok: true; port: number }
  | { ok: false; reason: 'EADDRINUSE' | 'ERROR'; error?: Error }

export class LoopbackServer {
  readonly #inboundQueue: MsgQueue
  readonly #port: number
  readonly #host: string
  readonly #path: string
  readonly #allowedOrigin: string
  readonly #log: (line: string) => void

  #wss: WebSocketServer | null = null
  /** 当前唯一的扩展连接。新连接到来会取代并关闭旧连接。 */
  #current: WebSocket | null = null
  /** 最近一次已知的后端段健康度,用于新扩展连接接入时立即补发(契约3)。 */
  #lastUpstream: 'up' | 'down' = 'down'

  constructor(opts: LoopbackServerOptions) {
    this.#inboundQueue = opts.inboundQueue
    this.#port = opts.port ?? LOOPBACK_PORT
    this.#host = opts.host ?? LOOPBACK_HOST
    this.#path = opts.path ?? LOOPBACK_PATH
    this.#allowedOrigin = opts.allowedOrigin ?? LOOPBACK_ALLOWED_ORIGIN
    this.#log = opts.log ?? ((line) => process.stderr.write(`${line}\n`))
  }

  /**
   * 启动 loopback WS server。
   *   - 监听成功 → resolve {ok:true}。
   *   - 端口被占(EADDRINUSE)→ resolve {ok:false, reason:'EADDRINUSE'} —— 单实例锁,
   *     调用方据此干净退出(已有常驻实例)。
   *   - 其它监听错误 → resolve {ok:false, reason:'ERROR', error}。
   */
  start(): Promise<StartResult> {
    return new Promise<StartResult>((resolve) => {
      const wss = new WebSocketServer({
        host: this.#host,
        port: this.#port,
        path: this.#path,
        // 握手 Origin 鉴权(契约2):异步形式以便用 403 明确拒绝。
        verifyClient: (
          info: { origin: string; secure: boolean; req: IncomingMessage },
          cb: (res: boolean, code?: number, message?: string) => void,
        ) => {
          // Chrome 扩展连 WS 时浏览器自动带 Origin: chrome-extension://<id>;严格相等才放行。
          // 注意:某些浏览器 origin 末尾可能不带斜杠,这里同时容忍带/不带末尾斜杠两种写法。
          const origin = (info.origin ?? '').replace(/\/$/, '')
          const allowed = this.#allowedOrigin.replace(/\/$/, '')
          if (origin === allowed) {
            cb(true)
          } else {
            this.#log(`[loopback] rejected handshake: origin='${info.origin}' (want '${this.#allowedOrigin}')`)
            cb(false, 403, 'forbidden origin')
          }
        },
      })
      this.#wss = wss

      // listen 错误:EADDRINUSE = 已有常驻实例;其它一律 ERROR。两者都 resolve(不抛),
      // 让调用方决定退出码,且只在"启动期"resolve 一次。
      const onListenError = (err: NodeJS.ErrnoException): void => {
        wss.off('listening', onListening)
        const reason = err.code === 'EADDRINUSE' ? 'EADDRINUSE' : 'ERROR'
        if (reason === 'EADDRINUSE') {
          this.#log(`[loopback] port ${this.#port} already in use — another resident bridge is running`)
          resolve({ ok: false, reason })
        } else {
          this.#log(`[loopback] listen error: ${String(err)}`)
          resolve({ ok: false, reason: 'ERROR', error: err })
        }
      }
      const onListening = (): void => {
        wss.off('error', onListenError)
        this.#log(`[loopback] listening on ws://${this.#host}:${this.#port}${this.#path}`)
        // 启动后把 error 监听换成"运行期错误"处理:运行期 error(极少见)只记日志,不退进程。
        wss.on('error', (e) => this.#log(`[loopback] server error: ${String(e)}`))
        resolve({ ok: true, port: this.#port })
      }

      wss.once('error', onListenError)
      wss.once('listening', onListening)

      wss.on('connection', (ws: WebSocket, req: IncomingMessage) => {
        this.#onConnection(ws, req)
      })
    })
  }

  /**
   * 接受一个扩展连接。新连接取代旧连接(扩展只应有一个 offscreen 在连)。取代时关闭旧连接,
   * 但【绝不】close 帧队列、【绝不】触发后端段任何动作(契约5)。
   */
  #onConnection(ws: WebSocket, req: IncomingMessage): void {
    this.#log(`[loopback] extension connected (url='${req.url ?? ''}')`)

    // 取代旧连接:先 detach 再关,避免旧连接的 close 处理误清掉新连接。
    const prev = this.#current
    if (prev && prev !== ws) {
      this.#detach(prev)
      try { prev.close(1000, 'superseded') } catch { /* ignore */ }
    }
    this.#current = ws

    // 新连接接入即补发一次最近已知 upstream 状态(契约3),让扩展立刻拿到后端死活。
    this.#sendUpstream(ws, this.#lastUpstream)

    ws.on('message', (data: WebSocket.RawData, isBinary: boolean) => {
      if (isBinary) {
        // 协议是 JSON 文本帧;二进制帧不符合契约,丢弃并记一条诊断,不杀连接。
        this.#log('[loopback] dropped unexpected binary frame')
        return
      }
      const raw = typeof data === 'string' ? data : data.toString()
      this.#onExtensionFrame(raw)
    })

    ws.on('close', () => {
      // 仅当关闭的是"当前"连接时才清空 current —— 单个扩展连接断开只 detach 这一段,
      // 帧队列与后端段毫发无伤(契约5:去 stdin-EOF=死)。
      if (this.#current === ws) {
        this.#current = null
        this.#log('[loopback] extension disconnected (current detached; backend link untouched)')
      }
    })

    ws.on('error', (e) => {
      // 连接级错误:记日志即可,close 会随后到来由上面的 close 处理器收尾。
      this.#log(`[loopback] connection error: ${String(e)}`)
    })
  }

  /** 处理一帧来自扩展的文本(EdgeMessage JSON)。 */
  #onExtensionFrame(raw: string): void {
    // ping/pong 是"扩展⇄bridge 段"的应用层保活,绝不上后端 WSS(与 NM 模式一致的过滤)。
    // loopback 段的真实保活由 WS 协议级 ping/pong 承担;此处即便扩展发了应用层 ping 也吞掉。
    if (isPingOrPongFrame(raw)) {
      return
    }
    const msg = parse(raw)
    if (!msg) {
      this.#log('[loopback] dropped unparseable extension frame')
      return
    }
    // 入队交给 Runner —— Runner 会盖上后端下发的 session_id 覆盖扩展传来的(保持现状安全语义),
    // 再上后端 WSS。loopback 不在这里改 session_id,职责单一。
    this.#inboundQueue.enqueue(msg)
  }

  /**
   * Runner 的 IpcDelegate.onInbound 回调实现:把一条后端下行 Message 转发回当前已连扩展。
   * 无扩展连接时静默丢弃(那是扩展⇄loopback 段的事,与后端段无关 —— 契约5)。
   */
  readonly onInbound = (msg: Message): void => {
    const ws = this.#current
    if (!ws || ws.readyState !== WebSocket.OPEN) return
    try {
      ws.send(JSON.stringify(msg))
    } catch (e) {
      this.#log(`[loopback] failed to forward inbound to extension: ${String(e)}`)
    }
  }

  /**
   * Runner 的 IpcDelegate.onUpstreamState 回调实现:后端段健康度变化时记录并推给扩展(契约3)。
   * 即便当前没有扩展连接也要记录 #lastUpstream,以便下一个扩展接入时补发。
   */
  readonly onUpstreamState = (state: 'up' | 'down'): void => {
    this.#lastUpstream = state
    const ws = this.#current
    if (ws) this.#sendUpstream(ws, state)
  }

  /** 向某个扩展连接发一帧 upstream 控制消息(契约3)。 */
  #sendUpstream(ws: WebSocket, state: 'up' | 'down'): void {
    if (ws.readyState !== WebSocket.OPEN) return
    // 用 edgeproto 的 make 构造合法信封;kind 走 Unknown 通道(协议未为 upstream 预留枚举),
    // 但 payload.kind 显式带 'upstream' + state,扩展侧据 payload 识别。这样既复用 EdgeMessage
    // JSON 形状(契约1),又不需要改 edgeproto 的 Kind 枚举(那会牵动 Java 侧契约)。
    const frame: Message = make({
      kind: Kind.Unknown,
      payload: { kind: UPSTREAM_KIND, state },
    })
    try {
      ws.send(JSON.stringify(frame))
    } catch (e) {
      this.#log(`[loopback] failed to send upstream state: ${String(e)}`)
    }
  }

  /** 拆掉一个连接的所有监听器(取代旧连接时用,避免旧 close 干扰新连接)。 */
  #detach(ws: WebSocket): void {
    ws.removeAllListeners('message')
    ws.removeAllListeners('close')
    ws.removeAllListeners('error')
  }

  /** 关闭 loopback server(进程收尾用)。不影响后端段。 */
  close(): Promise<void> {
    return new Promise<void>((resolve) => {
      const wss = this.#wss
      this.#wss = null
      const cur = this.#current
      this.#current = null
      if (cur) { try { cur.close(1001, 'shutdown') } catch { /* ignore */ } }
      if (!wss) { resolve(); return }
      wss.close(() => resolve())
    })
  }
}
