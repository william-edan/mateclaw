// OffscreenBridgeProxy — service-worker-side handle for the offscreen-hosted
// edge transport.
//
// Exposes the DirectBridgeClient surface (connect / send / onMessage /
// onDisconnect / onStateChange / disconnect / `connected`) so index.ts can swap
// transports without caring which is live. The actual WebSocket runs inside the
// offscreen document (see shared/offscreen-protocol.ts), so the socket survives
// SW idle-suspension.
//
// MV3-wake split of responsibility (IMPORTANT):
//   - INBOUND frames + connection-state relays are received by a TOP-LEVEL,
//     synchronously-registered chrome.runtime.onMessage listener in index.ts —
//     NOT here — because only a sync-registered listener reliably receives the
//     message that wakes a suspended SW. index.ts forwards state relays to this
//     proxy via {@link ingestRelay} (for the sidepanel pill + `connected`), and
//     dispatches inbound frames straight to the handlers.
//   - OUTBOUND frames are posted statelessly by index.ts's sendUp (so a reply
//     works even on a cold wake before this proxy is re-created).
// This proxy therefore owns only: ensuring the offscreen document exists,
// (idempotently) telling it to connect/disconnect, and tracking connection
// state for the pill.

import type { EdgeMessage } from '../shared/edge-protocol'
import type { BridgeState } from './direct-bridge'
import {
  OFFSCREEN_CONNECT,
  OFFSCREEN_DISCONNECT,
  OFFSCREEN_DISCONNECTED,
  OFFSCREEN_SEND,
  OFFSCREEN_STATE,
  OFFSCREEN_UPSTREAM_STATE,
  type OffscreenRelayMsg,
  type OffscreenTransport,
} from '../shared/offscreen-protocol'

/** Path of the offscreen document (must match the manifest/build output). */
const OFFSCREEN_URL = 'offscreen.html'
const OFFSCREEN_JUSTIFICATION =
  'Maintains the persistent edge WebSocket so the agent connection survives ' +
  'service-worker suspension.'

export interface OffscreenBridgeProxyDeps {
  deviceId: string
  deviceName?: string
  agentVersion: string
  /** Injectable for tests; defaults to the global chrome. */
  chrome?: typeof globalThis.chrome
}

export class OffscreenBridgeProxy {
  private readonly deviceId: string
  private readonly deviceName?: string
  private readonly agentVersion: string
  private readonly chromeApi: typeof globalThis.chrome

  /**
   * Connection state as relayed via OFFSCREEN_STATE.connected. For the DIRECT
   * transport this already means socket-OPEN + session-bound (end-to-end). For
   * the LOCAL transport it means only the loopback IPC socket is OPEN — the
   * end-to-end gate ALSO requires {@link _upstreamConnected} (契约3).
   */
  private _connected = false

  /**
   * Transport this proxy handle is driving. 'direct' (default) keeps the old
   * single-flag `connected`; 'local' switches `connected` to the end-to-end
   * gate (IPC OPEN ∧ upstream up). Set by {@link connect}.
   */
  private transport: OffscreenTransport = 'direct'

  /**
   * Last BACKEND (upstream) health relayed via OFFSCREEN_UPSTREAM_STATE (契约3).
   * Only meaningful for the local transport. Starts false so a fresh local
   * connection never reports connected:true on a bare loopback OPEN.
   */
  private _upstreamConnected = false

  private readonly messageCbs = new Set<(m: EdgeMessage) => void>()
  private readonly disconnectCbs = new Set<() => void>()
  private readonly stateCbs = new Set<(s: BridgeState) => void>()

  constructor(deps: OffscreenBridgeProxyDeps) {
    this.deviceId = deps.deviceId
    this.deviceName = deps.deviceName
    this.agentVersion = deps.agentVersion
    this.chromeApi = deps.chrome ?? globalThis.chrome
  }

  /**
   * END-TO-END connected.
   *   - direct transport: the relayed OPEN + session-bound flag.
   *   - local transport (契约3): loopback IPC OPEN *AND* the bridge reports its
   *     upstream (backend) link up. Reading only the IPC flag would be a false
   *     positive (假阳性) while the bridge is detached from the backend.
   */
  get connected(): boolean {
    if (this.transport === 'local') {
      return this._connected && this._upstreamConnected
    }
    return this._connected
  }

  /**
   * Ensure the offscreen document exists then (idempotently) ask it to connect.
   * Sync surface (matches DirectBridgeClient): the async ensure runs
   * fire-and-forget with error logging. The offscreen host treats a CONNECT
   * with the SAME transport+creds while already connected as a no-op + state
   * re-announce, so re-running this on every SW wake does NOT churn the socket.
   *
   * @param serverUrl backend WSS (direct) or loopback URL (local; '' ⇒ the
   *   bridge default ws://127.0.0.1:18077).
   * @param pat backend PAT (direct). Ignored by the local transport, which
   *   authenticates by Origin (契约2) — pass '' there.
   * @param transport 'direct' (default, 向后兼容) or 'local'.
   */
  connect(serverUrl: string, pat: string, transport: OffscreenTransport = 'direct'): void {
    this.transport = transport
    if (transport === 'local') this._upstreamConnected = false
    this.ensureDocument()
      .then(() => {
        this.post({
          type: OFFSCREEN_CONNECT,
          serverUrl,
          pat,
          deviceId: this.deviceId,
          deviceName: this.deviceName,
          agentVersion: this.agentVersion,
          transport,
        })
      })
      .catch(err => {
        console.error('[mateclaw][sw] offscreen ensure/connect failed', err)
        this._connected = false
        this._upstreamConnected = false
        this.stateCbs.forEach(cb => cb('closed'))
      })
  }

  /**
   * Post an outbound frame to the offscreen socket. index.ts's sendUp normally
   * posts directly (cold-wake-safe), so this is kept only for surface parity.
   */
  send(m: EdgeMessage): void {
    this.post({ type: OFFSCREEN_SEND, message: m })
  }

  /** Returns an unsubscribe function (matches DirectBridgeClient). */
  onMessage(cb: (m: EdgeMessage) => void): () => void {
    this.messageCbs.add(cb)
    return () => this.messageCbs.delete(cb)
  }

  onDisconnect(cb: () => void): void {
    this.disconnectCbs.add(cb)
  }

  onStateChange(cb: (s: BridgeState) => void): () => void {
    this.stateCbs.add(cb)
    return () => this.stateCbs.delete(cb)
  }

  /** Intentional teardown: tell the offscreen socket to close. */
  disconnect(): void {
    this.post({ type: OFFSCREEN_DISCONNECT })
    this._connected = false
    this._upstreamConnected = false
    this.stateCbs.forEach(cb => cb('closed'))
  }

  /**
   * Feed a connection-state relay (OFFSCREEN_STATE / OFFSCREEN_DISCONNECTED /
   * OFFSCREEN_UPSTREAM_STATE) received by index.ts's top-level listener. INBOUND
   * frames are NOT routed here — index.ts dispatches them straight to the
   * handlers. Other message shapes are ignored.
   */
  ingestRelay(raw: OffscreenRelayMsg): void {
    switch (raw.type) {
      case OFFSCREEN_STATE:
        this._connected = raw.connected
        this.stateCbs.forEach(cb => cb(raw.state))
        break
      case OFFSCREEN_UPSTREAM_STATE:
        // 契约3: BACKEND health update for the local transport. Re-emit a state
        // event so a consumer reading `connected` after the flip gets nudged —
        // 'open' when end-to-end up, 'closed' when the upstream went down even
        // though the local IPC socket is still alive.
        this._upstreamConnected = raw.upstreamConnected
        this.stateCbs.forEach(cb => cb(this.connected ? 'open' : 'closed'))
        break
      case OFFSCREEN_DISCONNECTED:
        this._connected = false
        this._upstreamConnected = false
        this.disconnectCbs.forEach(cb => cb())
        break
      default:
        break
    }
  }

  // ── internals ────────────────────────────────────────────────────────────

  private post(msg: unknown): void {
    this.chromeApi.runtime.sendMessage(msg).catch(() => {
      // Offscreen not listening this instant (rare, e.g. mid-create) — CONNECT
      // re-posts after ensureDocument resolves; dropped SENDs are reissued by
      // the server on its next request.
    })
  }

  /**
   * Ensure the single offscreen document exists. Idempotent + tolerant of the
   * "only a single offscreen document" race (two near-simultaneous creators).
   * Throws only when the offscreen API is entirely unavailable, so the caller
   * can fall back to an in-SW transport.
   */
  private async ensureDocument(): Promise<void> {
    const off = this.chromeApi.offscreen as
      | (typeof chrome.offscreen & { hasDocument?: () => Promise<boolean> })
      | undefined
    if (!off || typeof off.createDocument !== 'function') {
      throw new Error('chrome.offscreen unavailable')
    }
    try {
      if (typeof off.hasDocument === 'function' && (await off.hasDocument())) {
        return
      }
    } catch {
      // hasDocument unsupported/threw — fall through and try to create.
    }
    const reason =
      (off.Reason && off.Reason.BLOBS) ?? ('BLOBS' as chrome.offscreen.Reason)
    try {
      await off.createDocument({
        url: OFFSCREEN_URL,
        reasons: [reason],
        justification: OFFSCREEN_JUSTIFICATION,
      })
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      // A concurrent creator already made it — that's success for us.
      if (!/single offscreen|already exists|only.*one/i.test(msg)) {
        throw err
      }
    }
  }
}
