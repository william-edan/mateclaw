// Offscreen document — owns the persistent edge WebSocket.
//
// See shared/offscreen-protocol.ts for WHY the socket lives here instead of the
// service worker. This module is a thin transport host: it runs the chosen edge
// client (the real WS) and relays frames to/from the SW over chrome.runtime
// messaging. It performs NO privileged work — chrome.debugger / scripting / tabs
// are unavailable in an offscreen document and stay in the SW.
//
// Two transports are hosted here, selected by OFFSCREEN_CONNECT.transport
// (跨组契约):
//   - 'direct' (default) — DirectBridgeClient → backend WSS. Unchanged behaviour;
//     absent transport ⇒ direct, so a stale SW build keeps working (向后兼容).
//   - 'local'  — LocalBridgeClient → resident bridge loopback ws://127.0.0.1:18077.
//     Only this transport reports BACKEND (upstream) health, which we relay to the
//     SW via OFFSCREEN_UPSTREAM_STATE so end-to-end `connected` = local IPC OPEN
//     ∧ upstream up (契约3).

import { DirectBridgeClient } from '../sw/direct-bridge'
import { LocalBridgeClient } from '../sw/local-bridge'
import type { EdgeMessage } from '../shared/edge-protocol'
import {
  OFFSCREEN_CONNECT,
  OFFSCREEN_DISCONNECT,
  OFFSCREEN_DISCONNECTED,
  OFFSCREEN_INBOUND,
  OFFSCREEN_SEND,
  OFFSCREEN_STATE,
  OFFSCREEN_UPSTREAM_STATE,
  isOffscreenMsg,
  type OffscreenControlMsg,
  type OffscreenTransport,
} from '../shared/offscreen-protocol'

/** The edge clients we can host. Both expose the same connect/send/onMessage/
 *  onDisconnect/onStateChange/disconnect surface; LocalBridgeClient additionally
 *  has onUpstreamState + ipcOpen. */
type HostedBridge = DirectBridgeClient | LocalBridgeClient

let bridge: HostedBridge | null = null
/** Transport of the live `bridge`, so re-CONNECTs can detect a transport switch. */
let currentTransport: OffscreenTransport = 'direct'
let unsubMessage: (() => void) | null = null
let unsubState: (() => void) | null = null
let unsubUpstream: (() => void) | null = null
/** `${transport}\n${serverUrl}\n${pat}` of the live socket — gates idempotent reconnects. */
let currentCreds: string | null = null

/** Push a relay envelope up to the SW. Best-effort: a suspended SW is woken by
 *  the message; "no receiver" only happens transiently and is safe to drop. */
function relayToSw(msg: unknown): void {
  chrome.runtime.sendMessage(msg).catch(() => {
    // No SW listener in this instant (rare) — the next frame/heartbeat re-syncs.
  })
}

function teardown(): void {
  unsubMessage?.()
  unsubMessage = null
  unsubState?.()
  unsubState = null
  unsubUpstream?.()
  unsubUpstream = null
  try {
    bridge?.disconnect()
  } catch {
    // ignore
  }
  bridge = null
  currentCreds = null
}

/** True when the live bridge's LOCAL socket is OPEN (transport-agnostic). For the
 *  direct client `connected` already means socket-OPEN+session; for the local
 *  client we use ipcOpen so an idempotent re-CONNECT doesn't churn a live socket
 *  merely because upstream is momentarily down. */
function isLiveSocketOpen(b: HostedBridge): boolean {
  if (b instanceof LocalBridgeClient) return b.ipcOpen
  return b.connected
}

function onConnect(msg: Extract<OffscreenControlMsg, { type: typeof OFFSCREEN_CONNECT }>): void {
  const transport: OffscreenTransport = msg.transport ?? 'direct'
  const creds = `${transport}\n${msg.serverUrl}\n${msg.pat}`
  // Idempotent: the SW re-runs its startup connect on EVERY wake. If we are
  // already connected with the same transport+creds, don't churn the socket —
  // just re-announce state so the freshly-restarted SW (which lost its in-memory
  // state) learns the live connection status for its pill + isConnected().
  if (bridge && currentCreds === creds && isLiveSocketOpen(bridge)) {
    relayToSw({ type: OFFSCREEN_STATE, state: 'open', connected: bridge.connected })
    if (bridge instanceof LocalBridgeClient) {
      // Re-announce upstream too so the SW's end-to-end gate is restored (契约3).
      relayToSw({ type: OFFSCREEN_UPSTREAM_STATE, upstreamConnected: bridge.upstreamUp })
    }
    return
  }
  teardown()
  currentCreds = creds
  currentTransport = transport

  if (transport === 'local') {
    const client = new LocalBridgeClient()
    bridge = client
    unsubMessage = client.onMessage((m: EdgeMessage) => {
      relayToSw({ type: OFFSCREEN_INBOUND, message: m })
    })
    unsubState = client.onStateChange(state => {
      relayToSw({ type: OFFSCREEN_STATE, state, connected: client.connected })
    })
    // 契约3: relay BACKEND (upstream) health so the SW gate can combine it with
    // the local socket state. This is the signal that prevents the local-OPEN
    // false positive (假阳性).
    unsubUpstream = client.onUpstreamState(up => {
      relayToSw({ type: OFFSCREEN_UPSTREAM_STATE, upstreamConnected: up })
    })
    client.onDisconnect(() => {
      relayToSw({ type: OFFSCREEN_DISCONNECTED })
    })
    // No PAT on loopback: the bridge authenticates by Origin (契约2). serverUrl
    // is honoured if the SW passed one (defaults to ws://127.0.0.1:18077).
    client.connect(msg.serverUrl || undefined)
    return
  }

  const client = new DirectBridgeClient({
    deviceId: msg.deviceId,
    deviceName: msg.deviceName,
    agentVersion: msg.agentVersion,
  })
  bridge = client
  unsubMessage = client.onMessage((m: EdgeMessage) => {
    relayToSw({ type: OFFSCREEN_INBOUND, message: m })
  })
  unsubState = client.onStateChange(state => {
    relayToSw({ type: OFFSCREEN_STATE, state, connected: client.connected })
  })
  client.onDisconnect(() => {
    relayToSw({ type: OFFSCREEN_DISCONNECTED })
  })
  client.connect(msg.serverUrl, msg.pat)
}

chrome.runtime.onMessage.addListener((raw: unknown): undefined => {
  if (!isOffscreenMsg(raw)) return
  // Only SW→OFF control envelopes are actionable here; ignore our own relay
  // echoes and the SW's sidepanel `edge.inbound` broadcasts.
  switch (raw.type) {
    case OFFSCREEN_CONNECT:
      onConnect(raw)
      break
    case OFFSCREEN_SEND:
      try {
        bridge?.send(raw.message)
      } catch {
        // Bridge mid-reconnect — the server reissues on the next snapshot/action.
      }
      break
    case OFFSCREEN_DISCONNECT:
      teardown()
      break
    default:
      // OFFSCREEN_INBOUND / OFFSCREEN_STATE / OFFSCREEN_DISCONNECTED /
      // OFFSCREEN_UPSTREAM_STATE are OUR outbound types; never handled here.
      break
  }
  return undefined
})
