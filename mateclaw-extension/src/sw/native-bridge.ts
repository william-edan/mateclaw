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

const BACKOFF_BASE_MS = 1_000
const BACKOFF_CAP_MS = 30_000
/**
 * MV3 service-worker keep-alive cadence — kept identical to DirectBridgeClient.
 * Chrome terminates an idle MV3 service worker after ~30s; a bare
 * port.postMessage does NOT reset that idle timer, only a chrome.* API call (or
 * an inbound event) does. So while connected we tick a trivial chrome API call
 * comfortably under 30s, otherwise the SW dies mid-task, the native port closes,
 * and the next action has to relaunch the host.
 */
const KEEPALIVE_INTERVAL_MS = 20_000

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

  private readonly messageCbs = new Set<(m: EdgeMessage) => void>()
  private readonly disconnectCbs = new Set<() => void>()
  private readonly stateCbs = new Set<(s: BridgeState) => void>()

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
   * Open (or replace) the native-messaging port to {@link hostName}. A fresh
   * connect supersedes any pending reconnect + old port. Stores nothing extra:
   * the host name is final from the constructor.
   */
  connect(): void {
    // A fresh connect supersedes any pending reconnect/timers + old port.
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
    this.emitState('open')
    this.startKeepAlive()

    port.onMessage.addListener((raw: unknown) => {
      const m =
        typeof raw === 'string'
          ? parseEdgeMessage(raw)
          : parseEdgeMessage(JSON.stringify(raw))
      if (!m) return
      this.messageCbs.forEach(cb => cb(m))
    })

    port.onDisconnect.addListener(() => {
      // Ignore disconnects from a port we've already superseded/torn down.
      if (this.port !== port) return
      this.handleClose()
    })
  }

  private handleClose(): void {
    this.stopKeepAlive()
    this.port = null
    this._connected = false
    this.emitState('closed')
    this.disconnectCbs.forEach(cb => cb())
    if (!this.intentionalClose) {
      this.scheduleReconnect()
    }
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

  /** Subscribe to connecting/open/closed transitions. Returns unsubscribe. */
  onStateChange(cb: (s: BridgeState) => void): () => void {
    this.stateCbs.add(cb)
    return () => this.stateCbs.delete(cb)
  }

  private emitState(s: BridgeState): void {
    this.stateCbs.forEach(cb => cb(s))
  }

  /** Intentional teardown: stop timers, close the port, suppress reconnect. */
  disconnect(): void {
    this.intentionalClose = true
    this.clearTimers()
    this._connected = false
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
