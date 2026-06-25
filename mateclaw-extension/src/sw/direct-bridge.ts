// DirectBridgeClient — Phase 3.1 direct-WSS transport.
//
// Exposes the SAME public surface as NativeBridge (connect/send/onMessage/
// onDisconnect/disconnect, where onMessage returns an unsubscribe fn) so the
// service worker is transport-agnostic. Adds onStateChange + a `connected`
// getter for the sidepanel status pill and the `ping` external handler.
//
// Auth + handshake (see docs/specs/phase-3.1-contract.md §1):
//   - opens `new WebSocket(url, ['mateclaw.edge.v1', `bearer.${pat}`])`; the
//     PAT rides in Sec-WebSocket-Protocol, the server echoes `mateclaw.edge.v1`.
//   - on open → sends HELLO (session_id:"") with {agent_version, device_id,
//     device_name}.
//   - on HELLO_ACK → captures payload.session_id (STAMPS it on every subsequent
//     outbound frame, the job the Native Host did in NH mode) AND
//     payload.heartbeat_interval_ms, then starts the heartbeat.
//   - HEARTBEAT every heartbeat_interval_ms (server default 10s) while OPEN —
//     this is what keeps the session alive; the server reaps a session after
//     ~30s without one. (NOT app-level ping: the server does not treat ping as
//     liveness.) Exponential backoff reconnect on unexpected close
//     (1s→2s→…→30s cap) using the last serverUrl/pat, suppressed after an
//     intentional disconnect().

import type { EdgeMessage } from '../shared/edge-protocol'
import {
  EdgeMessageKind,
  makeEdgeMessage,
  parseEdgeMessage,
} from '../shared/edge-protocol'

export type BridgeState = 'connecting' | 'open' | 'closed'

/** Subprotocol the server must echo on the 101 response. */
export const EDGE_SUBPROTOCOL = 'mateclaw.edge.v1'

/** Fallback when HELLO_ACK doesn't carry heartbeat_interval_ms. Must be well
 *  under the server's ~30s stale-session reaper window. */
const DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000
const BACKOFF_BASE_MS = 1_000
const BACKOFF_CAP_MS = 30_000
/**
 * MV3 service-worker keep-alive cadence. Chrome terminates an idle MV3 service
 * worker after ~30s, and a bare WebSocket `send()` from a setInterval does NOT
 * reset that idle timer — only a chrome.* API call (or an inbound event) does.
 * So while connected we tick a trivial chrome API call comfortably under 30s.
 * Without this the SW dies mid-task during a long LLM turn, its socket closes
 * (server logs CloseStatus 1001 "going away"), and the next action has to
 * reconnect — the "中途断线重连" the user saw.
 */
const KEEPALIVE_INTERVAL_MS = 20_000

export interface DirectBridgeDeps {
  /** Stable per-install device id (ConfigStore.getDeviceId). */
  deviceId: string
  /** Human label for this device, if known. */
  deviceName?: string
  /** Extension version for the HELLO payload (manifest version). */
  agentVersion: string
  /** Injectable WebSocket ctor for tests; defaults to the global. */
  WebSocketImpl?: typeof WebSocket
  /**
   * Resets the MV3 idle timer to keep the service worker alive while connected.
   * Default calls a cheap chrome.* API (getPlatformInfo); injectable + a no-op
   * outside an extension (unit tests). Called every {@link KEEPALIVE_INTERVAL_MS}.
   */
  resetIdleTimer?: () => void
}

export class DirectBridgeClient {
  private ws: WebSocket | null = null
  private serverUrl: string | null = null
  private pat: string | null = null

  /** Server-issued session id captured from HELLO_ACK; "" until then. */
  private sessionId = ''

  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS
  private keepAliveTimer: ReturnType<typeof setInterval> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempt = 0
  /** Set by disconnect(); suppresses the auto-reconnect on the next close. */
  private intentionalClose = false

  private readonly messageCbs = new Set<(m: EdgeMessage) => void>()
  private readonly disconnectCbs = new Set<() => void>()
  private readonly stateCbs = new Set<(s: BridgeState) => void>()

  private readonly WebSocketImpl: typeof WebSocket
  private readonly deviceId: string
  private readonly deviceName?: string
  private readonly agentVersion: string
  private readonly resetIdleTimer: () => void

  constructor(deps: DirectBridgeDeps) {
    this.deviceId = deps.deviceId
    this.deviceName = deps.deviceName
    this.agentVersion = deps.agentVersion
    this.WebSocketImpl = deps.WebSocketImpl ?? WebSocket
    this.resetIdleTimer = deps.resetIdleTimer ?? defaultResetIdleTimer
  }

  /** True once the socket is OPEN and we have a server-issued session_id. */
  get connected(): boolean {
    return this.ws?.readyState === this.WebSocketImpl.OPEN && this.sessionId !== ''
  }

  /**
   * Connect (or replace the current connection) to `serverUrl` authenticating
   * with `pat`. Stores both for reconnect. Re-connecting tears down any prior
   * socket first.
   */
  connect(serverUrl: string, pat: string): void {
    // A fresh connect supersedes any pending reconnect/timers + old socket.
    this.clearTimers()
    this.intentionalClose = false
    if (this.ws) {
      // Detach our listeners before closing so the close doesn't trigger a
      // reconnect for the superseded socket.
      this.teardownSocket(this.ws)
      try {
        this.ws.close()
      } catch {
        // ignore
      }
    }
    this.serverUrl = serverUrl
    this.pat = pat
    this.openSocket()
  }

  private openSocket(): void {
    if (this.serverUrl === null || this.pat === null) return
    this.sessionId = ''
    this.emitState('connecting')

    const ws = new this.WebSocketImpl(this.serverUrl, [
      EDGE_SUBPROTOCOL,
      `bearer.${this.pat}`,
    ])
    this.ws = ws

    ws.onopen = () => {
      this.reconnectAttempt = 0
      this.emitState('open')
      // Heartbeat starts on HELLO_ACK (needs the session_id + advertised interval).
      // HELLO: session_id stays "" per protocol; server replies HELLO_ACK.
      this.rawSend(
        makeEdgeMessage({
          kind: EdgeMessageKind.Hello,
          payload: {
            agent_version: this.agentVersion,
            device_id: this.deviceId,
            device_name: this.deviceName ?? null,
          },
        }),
      )
    }

    ws.onmessage = (ev: MessageEvent) => {
      const raw = typeof ev.data === 'string' ? ev.data : String(ev.data)
      const m = parseEdgeMessage(raw)
      if (!m) return
      if (m.kind === EdgeMessageKind.HelloAck) {
        const sid = m.payload?.['session_id']
        if (typeof sid === 'string' && sid.length > 0) {
          this.sessionId = sid
        }
        const hb = m.payload?.['heartbeat_interval_ms']
        if (typeof hb === 'number' && hb > 0) {
          this.heartbeatIntervalMs = hb
        }
        // Now that we have a session_id to stamp, start protocol heartbeats so
        // the server's reaper doesn't drop us after the stale-session window.
        this.startHeartbeat()
        // The first OPEN state fires at the raw WebSocket level, before the
        // server-issued session_id arrives. Emit OPEN again after HELLO_ACK so
        // offscreen/SW status relays can report connected:true.
        this.emitState('open')
      }
      this.messageCbs.forEach(cb => cb(m))
    }

    ws.onclose = () => {
      this.handleClose()
    }

    ws.onerror = () => {
      // onerror is always followed by onclose; let handleClose drive reconnect.
    }
  }

  private handleClose(): void {
    this.stopHeartbeat()
    this.ws = null
    this.sessionId = ''
    this.emitState('closed')
    this.disconnectCbs.forEach(cb => cb())
    if (!this.intentionalClose) {
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect(): void {
    if (this.serverUrl === null || this.pat === null) return
    // 连远端 edge WSS:指数退避叠加 half-to-full jitter,避免大量安装在服务端重启后相位锁定、
    // 同一刻同时重连冲击(thundering herd)。下界保留 cap/2,避免抖动退化成 0 立即重连。
    const cap = Math.min(
      BACKOFF_BASE_MS * 2 ** this.reconnectAttempt,
      BACKOFF_CAP_MS,
    )
    const delay = cap / 2 + Math.random() * (cap / 2)
    this.reconnectAttempt += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.openSocket()
    }, delay)
  }

  private startHeartbeat(): void {
    this.stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      // Only meaningful once we have a session_id (rawSend stamps it); the
      // server's onHeartbeat validates the session before refreshing liveness.
      if (this.ws?.readyState === this.WebSocketImpl.OPEN && this.sessionId !== '') {
        this.rawSend(makeEdgeMessage({ kind: EdgeMessageKind.Heartbeat }))
      }
    }, this.heartbeatIntervalMs)
    // Separately keep the MV3 service worker alive while connected. The heartbeat
    // above is for SERVER liveness (a bare ws.send does NOT reset Chrome's 30s
    // SW idle timer); this ticks a chrome.* API under that window so the worker
    // — and thus the socket and pending actions — survive a long agent turn.
    this.startKeepAlive()
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

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
    this.stopKeepAlive()
  }

  private clearTimers(): void {
    this.stopHeartbeat()
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
   * Send an EdgeMessage. If we hold a captured session_id and the message's
   * session_id is "", stamp it before serialising — this is the NH bridge's old
   * role, now owned by the SW in direct mode.
   */
  send(m: EdgeMessage): void {
    if (!this.ws || this.ws.readyState !== this.WebSocketImpl.OPEN) {
      throw new Error('DirectBridgeClient.send: not connected')
    }
    this.rawSend(m)
  }

  /** Stamp + serialise without the OPEN guard (used for HELLO/Ping internally). */
  private rawSend(m: EdgeMessage): void {
    const stamped =
      this.sessionId !== '' && m.session_id === ''
        ? { ...m, session_id: this.sessionId }
        : m
    this.ws?.send(JSON.stringify(stamped))
  }

  /** Returns an unsubscribe function (matches NativeBridge). */
  onMessage(cb: (m: EdgeMessage) => void): () => void {
    this.messageCbs.add(cb)
    return () => this.messageCbs.delete(cb)
  }

  onDisconnect(cb: () => void): void {
    this.disconnectCbs.add(cb)
  }

  /** Subscribe to connecting/open/closed transitions. Returns unsubscribe. */
  onStateChange(cb: (s: BridgeState) => void): () => void {
    this.stateCbs.add(cb)
    return () => this.stateCbs.delete(cb)
  }

  private emitState(s: BridgeState): void {
    this.stateCbs.forEach(cb => cb(s))
  }

  /** Intentional teardown: stop timers, close socket, suppress reconnect. */
  disconnect(): void {
    this.intentionalClose = true
    this.stopKeepAlive()
    this.clearTimers()
    this.sessionId = ''
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

/**
 * Default {@link DirectBridgeDeps.resetIdleTimer}: a cheap chrome.* call whose
 * only purpose is to reset the MV3 service-worker idle timer. getPlatformInfo
 * has no side effects and is available without any permission. Guarded so it is
 * a harmless no-op outside an extension (unit tests) and never throws.
 *
 * Exported so {@link ./native-bridge.NativeBridge} can reuse the identical
 * MV3 keep-alive tick instead of duplicating it — both transports must keep the
 * service worker awake the same way.
 */
export function defaultResetIdleTimer(): void {
  try {
    const runtime = (globalThis as unknown as { chrome?: typeof chrome }).chrome?.runtime
    const result = runtime?.getPlatformInfo?.()
    // MV3 returns a Promise; swallow it so an idle-keepalive can't surface an
    // unhandled rejection. (Older callback signature returns undefined — fine.)
    if (result && typeof (result as Promise<unknown>).then === 'function') {
      ;(result as Promise<unknown>).then(
        () => {},
        () => {},
      )
    }
  } catch {
    // not in an extension context, or API unavailable — nothing to keep alive
  }
}
