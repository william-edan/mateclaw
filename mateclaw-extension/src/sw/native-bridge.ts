// NativeBridge wraps chrome.runtime.connectNative so the rest of the SW works
// in terms of EdgeMessages.
//
// Exposes the SAME public surface as DirectBridgeClient
// (connect/send/onMessage/onDisconnect/onStateChange/disconnect, where
// onMessage returns an unsubscribe fn, plus a `connected` getter) so index.ts
// stays transport-agnostic.
//
// Why reconnect + keepalive are mandatory here (not optional Phase-2 polish):
// Chrome lazily spawns the native host when the SW calls connectNative, and the
// port dies the moment the MV3 service worker is suspended (~30s idle). So we
//   1) keep the worker alive while connected by ticking a cheap chrome.* API
//      under the 30s window (a bare port.postMessage does NOT reset the idle
//      timer — only a chrome.* call or inbound event does), and
//   2) reconnect with exponential backoff (1s→2s→…→30s cap) on any unexpected
//      port disconnect, reusing the last host name, suppressed after an
//      intentional disconnect().
// This mirrors DirectBridgeClient's scheduleReconnect / startKeepAlive so the
// Native-Messaging path is as resilient as the direct-WSS path.

import type { EdgeMessage } from '../shared/edge-protocol'
import { parseEdgeMessage } from '../shared/edge-protocol'
import { defaultResetIdleTimer, type BridgeState } from './direct-bridge'

/**
 * NativeBridge can additionally report an `unpaired` state that the shared
 * {@link BridgeState} (connecting/open/closed) cannot express. It surfaces when
 * the bridge host answered with a non-retryable error (notably NO_TOKEN): the
 * host process is up but refuses to serve because it has no auth token, so
 * hammering it with 1s→30s reconnects is a pure spawn storm. We stay in
 * `unpaired` and switch to a sparse probe instead. Kept LOCAL to this file so
 * we don't widen the cross-transport BridgeState (consumed by the offscreen
 * proxy + protocol) — index.ts reads our `unpaired` getter, not this union.
 */
export type NativeBridgeState = BridgeState | 'unpaired'

const BACKOFF_BASE_MS = 1_000
const BACKOFF_CAP_MS = 30_000
/**
 * Sparse re-probe cadence used while {@link NativeBridge.unpaired} is true. The
 * host is up but unpaired (NO_TOKEN), so instead of the dense 1s→30s backoff we
 * poke it once every ~30s to detect when the user finally writes bridge.yaml /
 * sets the auth token, at which point the host stops sending NO_TOKEN and the
 * connection sticks. This is the anti-spawn-storm path for contract §2.
 */
const UNPAIRED_PROBE_INTERVAL_MS = 30_000
/**
 * MV3 service-worker keep-alive cadence — kept identical to DirectBridgeClient.
 * Chrome terminates an idle MV3 service worker after ~30s; a bare
 * port.postMessage does NOT reset that idle timer, only a chrome.* API call (or
 * an inbound event) does. So while connected we tick a trivial chrome API call
 * comfortably under 30s, otherwise the SW dies mid-task, the native port closes,
 * and the next action has to relaunch the host.
 */
const KEEPALIVE_INTERVAL_MS = 20_000
/**
 * Application-layer heartbeat cadence over the native port (contract §1). Every
 * tick we post {kind:'ping', ts} down the port; the bridge host echoes
 * {kind:'pong', ts}. The INBOUND pong is what actually renews the MV3 service
 * worker's idle timer — a plain chrome.* call keeps the worker alive only while
 * top-level code runs, but Chrome treats an inbound Native-Messaging message as
 * a genuine wake/keep event. So this ping is the real keep-alive; the
 * resetIdleTimer chrome.* tick is retained only as a belt-and-braces fallback.
 * Kept identical to KEEPALIVE_INTERVAL_MS so a single timer drives both.
 */
const PING_INTERVAL_MS = 20_000

export interface NativeBridgeDeps {
  /**
   * Resets the MV3 idle timer to keep the service worker alive while connected.
   * Default calls a cheap chrome.* API (getPlatformInfo); injectable + a no-op
   * outside an extension (unit tests). Called every {@link KEEPALIVE_INTERVAL_MS}.
   */
  resetIdleTimer?: () => void
  /**
   * Injectable chrome surface for tests; defaults to the global. Only
   * `runtime.connectNative` / `runtime.lastError` are used.
   */
  chrome?: typeof globalThis.chrome
}

export class NativeBridge {
  private port: chrome.runtime.Port | null = null

  /**
   * True once a port has been opened and not yet disconnected. Native Messaging
   * has no session handshake (the host owns the session_id), so unlike
   * DirectBridgeClient there is no post-ACK gate — port presence IS the
   * connected state.
   */
  private _connected = false

  private keepAliveTimer: ReturnType<typeof setInterval> | null = null
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempt = 0
  /** Set by disconnect(); suppresses the auto-reconnect on the next close. */
  private intentionalClose = false

  /**
   * True once the host answered with a non-retryable error (NO_TOKEN). While
   * set we DO NOT run the dense 1s→30s backoff; we run the sparse 30s probe
   * instead, and emitState reports 'unpaired'. Cleared on a successful open.
   */
  private _unpaired = false

  /** ts (ms) of the last inbound pong; 0 until one arrives. Diagnostic only. */
  private lastPongTs = 0

  private readonly messageCbs = new Set<(m: EdgeMessage) => void>()
  private readonly disconnectCbs = new Set<() => void>()
  private readonly stateCbs = new Set<(s: NativeBridgeState) => void>()

  private readonly resetIdleTimer: () => void
  private readonly chromeApi: typeof globalThis.chrome

  constructor(
    private readonly hostName: string,
    deps: NativeBridgeDeps = {},
  ) {
    this.resetIdleTimer = deps.resetIdleTimer ?? defaultResetIdleTimer
    this.chromeApi = deps.chrome ?? globalThis.chrome
  }

  /** True while a native port is open (no session gate — see field doc). */
  get connected(): boolean {
    return this._connected
  }

  /**
   * True when the host has declared itself non-retryably unavailable
   * (NO_TOKEN). index.ts reads this so the keepalive alarm runs the sparse
   * probe instead of a connect storm, and so the sidepanel/ping can surface
   * "未配对/未授权" rather than a generic "disconnected".
   */
  get unpaired(): boolean {
    return this._unpaired
  }

  /**
   * Open (or replace) the native-messaging port to {@link hostName}. A fresh
   * connect supersedes any pending reconnect + old port. Stores nothing extra:
   * the host name is final from the constructor.
   */
  connect(): void {
    // A fresh connect supersedes any pending reconnect/timers + old port. An
    // explicit connect() is a deliberate retry (e.g. user re-paired / the alarm
    // probe), so leave the unpaired latch in place — openPort() clears it only
    // once the host actually answers without a NO_TOKEN error. This keeps a
    // manual connect() during the unpaired window from masquerading as paired.
    this.clearTimers()
    this.intentionalClose = false
    if (this.port) {
      // Detach our listener-driven reconnect before tearing the old port down.
      const old = this.port
      this.port = null
      this._connected = false
      try {
        old.disconnect()
      } catch {
        // ignore
      }
    }
    this.openPort()
  }

  private openPort(): void {
    this.emitState('connecting')

    const port = this.chromeApi.runtime.connectNative(this.hostName)
    if (this.chromeApi.runtime.lastError) {
      // No port was established. Surface the failure as a closed state and let
      // backoff retry (unless this connect was intentional teardown — it isn't
      // here). Mirrors a synchronous open failure.
      this._connected = false
      this.emitState('closed')
      this.disconnectCbs.forEach(cb => cb())
      if (!this.intentionalClose) this.scheduleReconnect()
      return
    }
    this.port = port
    this._connected = true
    this.reconnectAttempt = 0
    // NOTE: we do NOT clear _unpaired here. connectNative succeeding only means
    // the host PROCESS spawned; an unpaired host still spawns, emits one
    // NO_TOKEN error frame, then exits. The latch is cleared only when a real
    // (non-error) inbound frame arrives — see handleInbound — which is the
    // genuine proof the host is serving.
    this.emitState(this._unpaired ? 'unpaired' : 'open')
    this.startKeepAlive()

    port.onMessage.addListener((raw: unknown) => this.handleInbound(raw))

    port.onDisconnect.addListener(() => {
      // Ignore disconnects from a port we've already superseded/torn down.
      if (this.port !== port) return
      this.handleClose()
    })
  }

  /**
   * Inbound from the native port. Three classes of frame are intercepted here
   * and NEVER forwarded to business listeners (contract §1/§2):
   *
   *   - lightweight pong {kind:'pong', ts}     — heartbeat ack, records liveness
   *   - lightweight ping {kind:'ping', ts}     — defensive; we are the pinger
   *   - error {kind:'error', retryable:false}  — host declared non-retryable
   *     (NO_TOKEN): latch unpaired + switch to the sparse probe
   *
   * "Lightweight" = a control frame that is NOT a full EdgeMessage (no v:1 /
   * msg_id). A *full* EdgeMessage whose kind happens to be 'pong' is a genuine
   * business heartbeat-ack and is forwarded normally — that distinction is why
   * we test for the control shape rather than the kind alone.
   */
  private handleInbound(raw: unknown): void {
    const obj = this.asObject(raw)

    if (obj) {
      const kind = obj.kind
      const isFullEdgeMessage = obj.v === 1 && typeof obj.msg_id === 'string'

      // Non-retryable error (NO_TOKEN). Accept both the flat contract shape
      // {kind:'error', code, retryable} and the full-EdgeMessage shape the
      // bridge currently emits ({v:1, kind:'error', payload:{code, retryable}}).
      if (kind === 'error') {
        const payload = (obj.payload ?? {}) as Record<string, unknown>
        const retryable = obj.retryable ?? payload.retryable
        const code = (obj.code ?? payload.code) as string | undefined
        if (retryable === false) {
          this.enterUnpaired(code)
          return // consumed — do not forward a NO_TOKEN error as a business frame
        }
        // retryable / unknown error: fall through to normal forwarding so the
        // upper layers keep their existing behaviour.
      }

      // Lightweight heartbeat frames are control-plane only — short-circuit so
      // they never reach business listeners and never get treated as edge work.
      if ((kind === 'pong' || kind === 'ping') && !isFullEdgeMessage) {
        if (kind === 'pong') this.notePong(obj.ts)
        return
      }
    }

    const m =
      typeof raw === 'string'
        ? parseEdgeMessage(raw)
        : parseEdgeMessage(JSON.stringify(raw))
    if (!m) return

    // Any genuine inbound EdgeMessage proves the host is actually serving, so a
    // stale unpaired latch (e.g. the user just configured the token and we
    // reconnected) is cleared here. A full-EdgeMessage pong counts too.
    if (m.kind === 'pong') this.notePong((m as unknown as { ts?: unknown }).ts)
    this.clearUnpaired()

    this.messageCbs.forEach(cb => cb(m))
  }

  /** Coerce an inbound frame to a plain record for control-frame inspection. */
  private asObject(raw: unknown): Record<string, unknown> | null {
    if (raw && typeof raw === 'object') return raw as Record<string, unknown>
    if (typeof raw === 'string') {
      try {
        const parsed = JSON.parse(raw)
        return parsed && typeof parsed === 'object'
          ? (parsed as Record<string, unknown>)
          : null
      } catch {
        return null
      }
    }
    return null
  }

  /** Record an inbound pong (the real MV3 keep-alive signal). */
  private notePong(ts: unknown): void {
    this.lastPongTs = typeof ts === 'number' ? ts : Date.now()
  }

  private handleClose(): void {
    this.stopKeepAlive()
    this.port = null
    this._connected = false
    if (this.intentionalClose) {
      this.emitState('closed')
      this.disconnectCbs.forEach(cb => cb())
      return
    }
    // Unpaired host (NO_TOKEN): the close was caused by the host refusing to
    // serve, not by a transient drop. Keep reporting 'unpaired' and re-probe
    // sparsely so we notice when the user finally configures the token, WITHOUT
    // a 1s spawn storm.
    if (this._unpaired) {
      this.emitState('unpaired')
      this.disconnectCbs.forEach(cb => cb())
      this.scheduleProbe()
      return
    }
    this.emitState('closed')
    this.disconnectCbs.forEach(cb => cb())
    this.scheduleReconnect()
  }

  /**
   * Latch the unpaired state (non-retryable host error, e.g. NO_TOKEN): stop the
   * dense backoff and emit 'unpaired'. The actual close that follows the host's
   * error frame is handled by handleClose, which then schedules the sparse probe.
   * Idempotent so repeated error frames don't churn timers.
   */
  private enterUnpaired(code?: string): void {
    if (this._unpaired) return
    this._unpaired = true
    // Cancel any pending dense reconnect — we don't want a 1s retry racing the
    // close that the error frame is about to cause.
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.reconnectAttempt = 0
    console.warn(
      `[mateclaw][native-bridge] host unpaired (code=${code ?? 'unknown'}) — switching to sparse probe`,
    )
    this.emitState('unpaired')
  }

  /** Clear the unpaired latch once the host is genuinely serving again. */
  private clearUnpaired(): void {
    if (!this._unpaired) return
    this._unpaired = false
  }

  private scheduleReconnect(): void {
    const delay = Math.min(
      BACKOFF_BASE_MS * 2 ** this.reconnectAttempt,
      BACKOFF_CAP_MS,
    )
    this.reconnectAttempt += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.openPort()
    }, delay)
  }

  /**
   * Sparse re-probe used in the unpaired state — a flat ~30s retry (no
   * exponential growth, no reset of the dense counter) that re-opens the port to
   * see whether the host now has a token. The first non-error inbound frame
   * clears the latch and we fall back to the normal connected path.
   */
  private scheduleProbe(): void {
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.openPort()
    }, UNPAIRED_PROBE_INTERVAL_MS)
  }

  private startKeepAlive(): void {
    this.stopKeepAlive()
    this.keepAliveTimer = setInterval(() => {
      // Belt-and-braces chrome.* tick (kept for back-compat / fallback).
      this.resetIdleTimer()
      // Contract §1: the REAL keep-alive — an outbound ping whose inbound pong
      // is the event Chrome actually counts as SW activity. Guarded by a live
      // port so a tick during a reconnect window is a silent no-op.
      this.sendPing()
    }, KEEPALIVE_INTERVAL_MS)
  }

  /** Post a lightweight {kind:'ping', ts} control frame down the native port. */
  private sendPing(): void {
    const port = this.port
    if (!port) return
    try {
      port.postMessage({ kind: 'ping', ts: Date.now() })
    } catch {
      // Port may have died between the guard and the post during a teardown
      // race; the onDisconnect path will drive reconnect. Swallow.
    }
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

  send(m: EdgeMessage): void {
    if (!this.port) throw new Error('NativeBridge.send: not connected')
    this.port.postMessage(m)
  }

  /** Returns an unsubscribe function (matches DirectBridgeClient). */
  onMessage(cb: (m: EdgeMessage) => void): () => void {
    this.messageCbs.add(cb)
    return () => this.messageCbs.delete(cb)
  }

  onDisconnect(cb: () => void): void {
    this.disconnectCbs.add(cb)
  }

  /**
   * Subscribe to connecting/open/closed transitions, plus the NativeBridge-only
   * 'unpaired' state. Returns unsubscribe. The callback parameter is the
   * widened {@link NativeBridgeState}; a BridgeState-only consumer still works
   * because BridgeState is a subset.
   */
  onStateChange(cb: (s: NativeBridgeState) => void): () => void {
    this.stateCbs.add(cb)
    return () => this.stateCbs.delete(cb)
  }

  private emitState(s: NativeBridgeState): void {
    this.stateCbs.forEach(cb => cb(s))
  }

  /** Intentional teardown: stop timers, close the port, suppress reconnect. */
  disconnect(): void {
    this.intentionalClose = true
    this.clearTimers()
    this._connected = false
    // An explicit teardown clears the unpaired latch: the next connect() is a
    // clean slate (e.g. unpair → re-pair, or a switch to the direct transport).
    this._unpaired = false
    if (this.port) {
      const old = this.port
      this.port = null
      try {
        old.disconnect()
      } catch {
        // ignore
      }
    }
    this.emitState('closed')
  }
}
