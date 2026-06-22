// LocalBridgeClient — resident-bridge loopback transport (连接根治 · 组3).
//
// A trimmed sibling of {@link ./direct-bridge.DirectBridgeClient}. Where Direct
// speaks the full edge protocol to the BACKEND over WSS (HELLO → HELLO_ACK →
// session_id stamping → server heartbeat), this client speaks to the LOCAL,
// always-on bridge process over a loopback WebSocket (跨组契约1:
// ws://127.0.0.1:18077). The bridge — not the extension — owns the single live
// connection to the backend; the extension just feeds Edge frames into the local
// socket and the bridge proxies them upstream.
//
// What is DELIBERATELY removed vs DirectBridgeClient (跨组契约5 生命周期硬隔离):
//   - NO HELLO / HELLO_ACK handshake. The bridge↔backend session is established
//     and owned by the bridge; the extension↔bridge loopback segment carries NO
//     session of its own.
//   - NO session_id capture or outbound stamping. Frames go out verbatim; the
//     bridge stamps/owns the real backend session_id on its side.
//   - NO backend Heartbeat. The extension must NEVER drive backend liveness over
//     this segment — that is the bridge's job. Driving it here would couple the
//     two connections' lifecycles (the exact thing 契约5 forbids: a loopback
//     blip must not bubble into a backend reconnect / HELLO replay / 4409 evict).
//
// What is KEPT:
//   - The same public surface (connect/send/onMessage/onDisconnect/
//     onStateChange/disconnect + `connected`) so index.ts stays transport-agnostic.
//   - The MV3 keep-alive tick — now LOAD-BEARING (SW-direct: the socket lives in
//     the SW, not an offscreen doc). It starts the moment we begin connecting
//     (not just after OPEN) and runs across reconnect gaps, resetting the ~30s SW
//     idle timer so the tight reconnect below survives long enough to reach a
//     bridge that starts AFTER the extension (the "装完扩展再开服务" cold-start).
//   - Auto-reconnect on unexpected close, but with TIGHTER backoff (100ms → 2s):
//     the bridge is local and (when resident) effectively always up, so we
//     reconnect aggressively rather than the WSS 1s→30s curve.
//
// What is ADDED:
//   - Upstream health (跨组契约3, 端到端 isConnected 的关键). The bridge pushes a
//     control frame {kind:"upstream", state:"up"|"down"} over the loopback to
//     report whether ITS connection to the backend is healthy. We intercept those
//     frames (they are NOT business EdgeMessages — they never reach onMessage),
//     track the last state in {@link #upstreamConnected}, and surface it via
//     onUpstreamState so the offscreen host can relay it to the SW. The SW's
//     end-to-end `connected` is then (local IPC OPEN ∧ upstream up) — never just
//     the local socket being OPEN, which would be a false positive while the
//     bridge is detached from the backend.

import type { EdgeMessage } from '../shared/edge-protocol'
import { parseEdgeMessage } from '../shared/edge-protocol'
import { defaultResetIdleTimer, type BridgeState } from './direct-bridge'

/**
 * Loopback endpoint of the resident bridge's WS server (跨组契约1, 组1 定义).
 * 必须带 `/bridge` 路径 —— loopback server 用 `ws` 的 `path:'/bridge'` 校验握手,
 * 连到 `ws://127.0.0.1:18077`(裸根 `/`)会被直接拒握手(从不 emit 'connection'),
 * 表现为扩展疯狂重连(18077 上一堆 TIME_WAIT)+ 后端动作石沉大海(no cancel ack)。
 * 单一真源,勿再在别处复制此字面量。
 */
export const LOCAL_BRIDGE_URL = 'ws://127.0.0.1:18077/bridge'

/**
 * Control-frame kind the bridge uses to report backend (upstream) health over
 * the loopback (跨组契约3). Carried OUTSIDE the EdgeMessage business stream so it
 * never lands in onMessage — `{ kind: 'upstream', state: 'up' | 'down' }`.
 */
const UPSTREAM_KIND = 'upstream' as const

/**
 * Tight reconnect curve for the LOCAL socket: 100ms → 2s cap. The bridge is on
 * loopback and, when resident, effectively always available, so we re-probe
 * aggressively instead of the WSS 1s→30s backoff.
 */
const BACKOFF_BASE_MS = 100
const BACKOFF_CAP_MS = 2_000

/**
 * MV3 keep-alive cadence. The loopback socket is held DIRECTLY by the SW (no
 * offscreen), so this tick is load-bearing: started at connect time and kept
 * running across reconnect gaps, it resets the ~30s SW idle timer every 20s so
 * the fast reconnect survives long enough to catch a bridge that comes up after
 * the extension. 20s < 30s idle on purpose.
 */
const KEEPALIVE_INTERVAL_MS = 20_000

export interface LocalBridgeDeps {
  /** Injectable WebSocket ctor for tests; defaults to the global. */
  WebSocketImpl?: typeof WebSocket
  /**
   * Resets the MV3 idle timer to keep the service worker alive while connected.
   * Default calls a cheap chrome.* API (getPlatformInfo); injectable + a no-op
   * outside an extension (unit tests). Called every {@link KEEPALIVE_INTERVAL_MS}.
   */
  resetIdleTimer?: () => void
}

export class LocalBridgeClient {
  private ws: WebSocket | null = null
  private serverUrl: string | null = null

  /**
   * Last reported BACKEND (upstream) health from the bridge (跨组契约3). False
   * until the bridge sends its first {kind:'upstream', state:'up'}. We start
   * pessimistic so `connected` never reads true on a fresh loopback OPEN before
   * the bridge has confirmed it is actually attached to the backend.
   */
  private upstreamConnected = false

  private keepAliveTimer: ReturnType<typeof setInterval> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempt = 0
  /** Set by disconnect(); suppresses the auto-reconnect on the next close. */
  private intentionalClose = false

  private readonly messageCbs = new Set<(m: EdgeMessage) => void>()
  private readonly disconnectCbs = new Set<() => void>()
  private readonly stateCbs = new Set<(s: BridgeState) => void>()
  private readonly upstreamCbs = new Set<(up: boolean) => void>()

  private readonly WebSocketImpl: typeof WebSocket
  private readonly resetIdleTimer: () => void

  constructor(deps: LocalBridgeDeps = {}) {
    this.WebSocketImpl = deps.WebSocketImpl ?? WebSocket
    this.resetIdleTimer = deps.resetIdleTimer ?? defaultResetIdleTimer
  }

  /**
   * END-TO-END connected (跨组契约3): the local IPC socket is OPEN *AND* the
   * bridge has reported its upstream (backend) link is up. Reading only the
   * socket's OPEN state would be a false positive whenever the bridge is detached
   * from the backend — the very假阳性 this whole change exists to kill.
   */
  get connected(): boolean {
    return (
      this.ws?.readyState === this.WebSocketImpl.OPEN && this.upstreamConnected
    )
  }

  /** Whether the local IPC socket alone is OPEN, ignoring upstream health. */
  get ipcOpen(): boolean {
    return this.ws?.readyState === this.WebSocketImpl.OPEN
  }

  /** Last upstream (backend) health reported by the bridge (跨组契约3). */
  get upstreamUp(): boolean {
    return this.upstreamConnected
  }

  /**
   * Connect (or replace the current connection) to the loopback `serverUrl`
   * (defaults to {@link LOCAL_BRIDGE_URL}). No PAT: the loopback server
   * authenticates by Origin (跨组契约2 — Chrome sends the fixed extension Origin
   * automatically), so the extension passes no secret. Re-connecting tears down
   * any prior socket first.
   */
  connect(serverUrl: string = LOCAL_BRIDGE_URL): void {
    this.clearTimers()
    this.intentionalClose = false
    if (this.ws) {
      this.teardownSocket(this.ws)
      try {
        this.ws.close()
      } catch {
        // ignore
      }
    }
    this.serverUrl = serverUrl
    this.openSocket()
  }

  private openSocket(): void {
    if (this.serverUrl === null) return
    // A reconnect of the LOCAL segment must reset our view of upstream health:
    // we don't know the backend's state until the bridge re-announces it. (This
    // is purely our local bookkeeping — it does NOT touch the bridge↔backend
    // connection, 契约5.)
    this.upstreamConnected = false
    this.emitState('connecting')
    // 关键(冷启时序):连接/重连阶段就开始 keepalive,维持 SW 存活,让 100ms→2s 快速重连
    // 能一直跑到 bridge 出现。SW-direct 下 socket 在 SW 里,SW 被回收=重连定时器消失;若只在
    // onopen 才 keepalive,bridge 比扩展晚起时 SW 会先被回收,只能等 30s alarm(="先装扩展
    // 再开服务要等一会儿")。openSocket 每次重连都会到此,startKeepAlive 自带去重(先 stop
    // 再起),不会叠定时器。
    this.startKeepAlive()

    // No subprotocols / no bearer: loopback auth is by Origin (契约2).
    const ws = new this.WebSocketImpl(this.serverUrl)
    this.ws = ws

    ws.onopen = () => {
      this.reconnectAttempt = 0
      // 上报扩展版本给 bridge(loopback 控制帧 {kind:'ext_hello', extension_version},非业务
      // EdgeMessage;bridge 拦截、不上后端业务流)。bridge 再经 heartbeat 把 extension_version 转报
      // 后端 → UI 显示/校验扩展版本,自诊断"exe 与扩展版本错配"(测试者最常见:扩展是旧的)。
      try {
        const version =
          typeof chrome !== 'undefined' && chrome.runtime?.getManifest
            ? chrome.runtime.getManifest().version
            : ''
        if (version) ws.send(JSON.stringify({ kind: 'ext_hello', extension_version: version }))
      } catch {
        // 无 chrome(单测)/ 发送失败:忽略 —— 版本上报是诊断增强,非连接必需。
      }
      // Loopback socket OPEN — but NOT yet end-to-end connected. We wait for the
      // bridge's {kind:'upstream'} frame before `connected` flips true. Emit
      // 'open' for the local socket so the offscreen relay can report ipcOpen,
      // and start the MV3 keep-alive.
      this.emitState('open')
      this.startKeepAlive()
    }

    ws.onmessage = (ev: MessageEvent) => {
      const raw = typeof ev.data === 'string' ? ev.data : String(ev.data)
      // Intercept the upstream-health control frame BEFORE EdgeMessage parsing:
      // it is a control-plane signal (契约3), not business traffic, and must
      // never reach onMessage listeners.
      if (this.tryHandleUpstream(raw)) return
      const m = parseEdgeMessage(raw)
      if (!m) return
      this.messageCbs.forEach(cb => cb(m))
    }

    ws.onclose = () => {
      this.handleClose()
    }

    ws.onerror = () => {
      // onerror is always followed by onclose; let handleClose drive reconnect.
    }
  }

  /**
   * If `raw` is the bridge's upstream-health control frame
   * `{ kind: 'upstream', state: 'up' | 'down' }` (契约3), record the new state,
   * notify subscribers, and return true (consumed — do not forward). A genuine
   * EdgeMessage has v:1 + msg_id and is never mistaken for this control frame.
   */
  private tryHandleUpstream(raw: string): boolean {
    let obj: unknown
    try {
      obj = JSON.parse(raw)
    } catch {
      return false
    }
    if (!obj || typeof obj !== 'object') return false
    const rec = obj as Record<string, unknown>
    // A full EdgeMessage carries v:1 + msg_id; never treat it as a control frame.
    if (rec.v === 1 && typeof rec.msg_id === 'string') return false
    if (rec.kind !== UPSTREAM_KIND) return false
    const up = rec.state === 'up'
    if (up !== this.upstreamConnected) {
      this.upstreamConnected = up
      this.upstreamCbs.forEach(cb => cb(up))
      // Re-announce socket state so a consumer reading `connected` after the
      // flip gets a fresh state event to act on (open while up, still-open
      // while down — the consumer keys on the upstream callback for the gate).
      this.emitState(this.ipcOpen ? 'open' : 'closed')
    }
    return true
  }

  private handleClose(): void {
    this.ws = null
    // Local segment down ⇒ we no longer know the backend's health from here.
    this.upstreamConnected = false
    this.emitState('closed')
    this.disconnectCbs.forEach(cb => cb())
    if (this.intentionalClose) {
      // 主动断开:停掉 keepalive,放 SW 回收(disconnect() 已 clearTimers,这里兜底)。
      this.stopKeepAlive()
      return
    }
    // 自动重连:【不】停 keepalive —— 连接中/重连阶段也要维持 SW 存活,否则 SW 会在
    // bridge 起来前被回收,100ms→2s 快速重连随之消失,只能等 30s keepalive alarm
    // (就是"先装扩展、再开服务要等一会儿/必须 reload 才连上"的根因)。
    this.scheduleReconnect()
  }

  private scheduleReconnect(): void {
    if (this.serverUrl === null) return
    const delay = Math.min(
      BACKOFF_BASE_MS * 2 ** this.reconnectAttempt,
      BACKOFF_CAP_MS,
    )
    this.reconnectAttempt += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.openSocket()
    }, delay)
  }

  private startKeepAlive(): void {
    this.stopKeepAlive()
    this.keepAliveTimer = setInterval(() => {
      this.resetIdleTimer()
    }, KEEPALIVE_INTERVAL_MS)
  }

  private stopKeepAlive(): void {
    if (this.keepAliveTimer !== null) {
      clearInterval(this.keepAliveTimer)
      this.keepAliveTimer = null
    }
  }

  private clearTimers(): void {
    this.stopKeepAlive()
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.reconnectAttempt = 0
  }

  private teardownSocket(ws: WebSocket): void {
    ws.onopen = null
    ws.onmessage = null
    ws.onclose = null
    ws.onerror = null
  }

  /**
   * Send an EdgeMessage verbatim over the loopback. NO session_id stamping (the
   * bridge owns the backend session). Throws if the local socket isn't OPEN.
   */
  send(m: EdgeMessage): void {
    if (!this.ws || this.ws.readyState !== this.WebSocketImpl.OPEN) {
      throw new Error('LocalBridgeClient.send: not connected')
    }
    this.ws.send(JSON.stringify(m))
  }

  /** Returns an unsubscribe function (matches the other transports). */
  onMessage(cb: (m: EdgeMessage) => void): () => void {
    this.messageCbs.add(cb)
    return () => this.messageCbs.delete(cb)
  }

  onDisconnect(cb: () => void): void {
    this.disconnectCbs.add(cb)
  }

  /** Subscribe to connecting/open/closed transitions of the LOCAL socket. */
  onStateChange(cb: (s: BridgeState) => void): () => void {
    this.stateCbs.add(cb)
    return () => this.stateCbs.delete(cb)
  }

  /**
   * Subscribe to BACKEND (upstream) health changes reported by the bridge
   * (契约3). The offscreen host relays these to the SW so the end-to-end
   * `connected` gate can combine them with the local socket state.
   */
  onUpstreamState(cb: (up: boolean) => void): () => void {
    this.upstreamCbs.add(cb)
    return () => this.upstreamCbs.delete(cb)
  }

  private emitState(s: BridgeState): void {
    this.stateCbs.forEach(cb => cb(s))
  }

  /** Intentional teardown: stop timers, close socket, suppress reconnect. */
  disconnect(): void {
    this.intentionalClose = true
    this.clearTimers()
    this.upstreamConnected = false
    if (this.ws) {
      this.teardownSocket(this.ws)
      try {
        this.ws.close()
      } catch {
        // ignore
      }
      this.ws = null
    }
    this.emitState('closed')
  }
}
