// Service Worker entry point — Chrome MV3 background service worker.
//
// Phase 3.1: owns the transport to the backend. Transports share one public
// surface (connect/send/onMessage/onStateChange/onDisconnect/disconnect):
//   - NativeBridge — Chrome Native Messaging to the local bridge host. Now the
//     STARTUP transport: connectNative() runs at SW load so the desktop
//     ("装好即连") path comes online with no pairing/config.
//   - DirectBridgeClient / OffscreenBridgeProxy — direct WSS to
//     /api/v1/browser/edge. Retained for the paired / Claude-Code path and
//     reachable via pair/unpair, but NO LONGER auto-connected on SW start.
//
// Wires the TabGroupManager + DebuggerManager + ActionExecutor + ActionRouter
// + snapshot/screenshot/visual handlers to whichever transport is active, and
// forwards inbound Edge messages to the sidepanel. Also exposes the
// externally_connectable ping/pair/unpair protocol for the admin UI (§2).

import { EdgeMessageKind, makeEdgeMessage, type EdgeMessage } from '../shared/edge-protocol'
import { NativeBridge } from './native-bridge'
import { DirectBridgeClient } from './direct-bridge'
import { OffscreenBridgeProxy } from './offscreen-bridge-proxy'
import {
  OFFSCREEN_DISCONNECTED,
  OFFSCREEN_INBOUND,
  OFFSCREEN_SEND,
  OFFSCREEN_STATE,
  isOffscreenMsg,
} from '../shared/offscreen-protocol'
import { ConfigStore } from './config-store'
import { TabGroupManager } from './tab-group-manager'
import { DebuggerManager } from './debugger-manager'
import { ActionExecutor, type ActionHandlers } from './action/ActionExecutor'
import { TabRefResolver } from './action/tab-ref-resolver'
import { ActionRouter } from './action/action-router'
import { SnapshotRequestHandler } from './snapshot-request-handler'
import { ScreenshotCaptureHandler } from './screenshot-capture-handler'
import { VisualCoordinator } from './visual-coordinator'
import { navigateHandler } from './action/handlers/navigate'
import { clickHandler } from './action/handlers/click'
import { typeHandler } from './action/handlers/type'
import { pressKeyHandler } from './action/handlers/press_key'
import { scrollHandler } from './action/handlers/scroll'
import { scrollRegionHandler } from './action/handlers/scroll_region'
import { registerRegionHandler } from './action/handlers/register_region'
import { detectRegionHandler } from './action/handlers/detect_region'
import { extractRegionHandler } from './action/handlers/extract_region'
import { openAuthorFromCommentHandler } from './action/handlers/open_author_from_comment'
import { clickProfileActionHandler } from './action/handlers/click_profile_action'
import { typeDmDraftHandler } from './action/handlers/type_dm_draft'
import { closeTabHandler } from './action/handlers/close_tab'
import { moveMouseHandler, viewportCenterFromDebugger } from './action/handlers/move_mouse'
import { waitHandler } from './action/handlers/wait'
import { douyinCommentNetworkHandler } from './action/handlers/douyin_comment_network'
import { douyinSearchHandler } from './action/handlers/douyin_search'
import { douyinOpenVideoHandler } from './action/handlers/douyin_open_video'
import { douyinUiHandler } from './action/handlers/douyin_ui'
import type { Point } from '../lib/windmouse'
import { RegionRegistry } from '../runtime/region-registry'
import { parseRegionClearMessage, parseRegionRegistrationMessage } from '../runtime/messages'

/** Canonical NM host name — must match com.mateclaw.browser_bridge manifest. */
const HOST = 'com.mateclaw.browser_bridge'

/**
 * Phase 2 hardcoded subject. Phase 4 will derive this from the
 * authenticated user (sidepanel auth flow). For now everything routes
 * to a single "default" subject so the TabGroupManager has somewhere to
 * stash main-tab bindings.
 */
const SUBJECT = 'default'

/**
 * Origins allowed to talk to us via chrome.runtime.sendMessage (externally
 * connectable). MUST mirror manifest `externally_connectable.matches`. The
 * sender.origin Chrome reports has no trailing path, so compare bare origins.
 */
const ALLOWED_EXTERNAL_ORIGINS = new Set<string>([
  'http://localhost:18088',
  'http://localhost:5173',
  'http://localhost:18080',
  'http://127.0.0.1:18088',
  'http://127.0.0.1:5173',
  'http://127.0.0.1:18080',
])

// -----------------------------------------------------------------
// Transport: a single active bridge, swappable at runtime (pair/unpair).
// sendUp + dispatchInbound indirect through whatever is active so the
// handler wiring below never needs to know which transport is live.
// -----------------------------------------------------------------

type Bridge = DirectBridgeClient | OffscreenBridgeProxy | NativeBridge

const configStore = new ConfigStore()
let activeBridge: Bridge | null = null
let bridgeUnsub: (() => void) | null = null

/**
 * Whether this runtime hosts the socket in an offscreen document. Resolved
 * SYNCHRONOUSLY at SW load (chrome.offscreen is available pre-startup), so both
 * the top-level relay listener and sendUp can rely on it before the async
 * startup connect runs — critical for cold-wake correctness.
 */
const usingOffscreen = typeof chrome.offscreen?.createDocument === 'function'

const sendUp = (msg: EdgeMessage): void => {
  try {
    // Route to the offscreen document ONLY when an offscreen-hosted transport
    // actually owns the socket: either the active bridge IS the offscreen proxy,
    // or this is a cold SW wake before startup rebuilt the proxy (activeBridge
    // still null) on an offscreen-capable runtime — there the offscreen doc may
    // hold the socket and the reply must not wait for reconnection (stateless on
    // purpose). The in-SW transports — NativeBridge on the desktop path, or the
    // fallback in-SW DirectBridgeClient — own their socket inside the worker, so
    // they send directly.
    //
    // Keying this on `usingOffscreen` alone (the old code) silently dropped EVERY
    // outbound frame on the native path: usingOffscreen is true on chrome116+, but
    // connectNative never creates an offscreen document, so action.result/snapshot/
    // heartbeat/HELLO were all posted into the void and swallowed by .catch() —
    // the desktop "connected but no round-trips" failure.
    if (activeBridge instanceof OffscreenBridgeProxy || (!activeBridge && usingOffscreen)) {
      chrome.runtime.sendMessage({ type: OFFSCREEN_SEND, message: msg }).catch(() => {})
    } else {
      activeBridge?.send(msg)
    }
  } catch (e) {
    // Best-effort — bridge may be disconnected during a reconnect window.
    console.error('[mateclaw][sw] sendUp failed', e)
  }
}

// MV3-CRITICAL: register the offscreen relay listener SYNCHRONOUSLY at SW load.
// The offscreen document owns the socket and posts inbound frames here; an
// inbound chrome.runtime message is what WAKES a suspended SW, and Chrome only
// delivers that waking message to listeners registered during the synchronous
// top-level execution. A listener added later (e.g. inside connectDirect's async
// chain) would miss the very frame that woke us. dispatchInbound + every handler
// instance are constructed synchronously below at module load, so dispatching
// here is safe even before the (async) startup connect has run.
if (usingOffscreen) {
  chrome.runtime.onMessage.addListener((raw: unknown): undefined => {
    if (!isOffscreenMsg(raw)) return
    if (raw.type === OFFSCREEN_INBOUND) {
      dispatchInbound(raw.message)
    } else if (raw.type === OFFSCREEN_STATE || raw.type === OFFSCREEN_DISCONNECTED) {
      // Connection-state relays drive the sidepanel pill + isConnected().
      if (activeBridge instanceof OffscreenBridgeProxy) activeBridge.ingestRelay(raw)
    }
    return undefined
  })
}

/** Resolve the extension version for the HELLO payload. */
function agentVersion(): string {
  try {
    return chrome.runtime.getManifest().version
  } catch {
    return '0.0.0'
  }
}

/**
 * (Re)connect the direct WSS transport. Tears down any previous bridge's
 * inbound subscription, builds a fresh DirectBridgeClient, re-subscribes the
 * inbound dispatcher, and connects.
 */
async function connectDirect(serverUrl: string, pat: string): Promise<void> {
  const cfg = await configStore.getConfig()
  // Drop the previous bridge subscription + connection.
  bridgeUnsub?.()
  bridgeUnsub = null
  if (activeBridge && 'disconnect' in activeBridge) {
    try {
      activeBridge.disconnect()
    } catch {
      // ignore
    }
  }

  // Prefer the offscreen-hosted socket: it survives SW idle-suspension, which
  // is the root fix for the mid-task "断线重连". Fall back to the in-SW
  // DirectBridgeClient (keepalive-guarded) only when the offscreen API is
  // unavailable (pre-116 / disabled) so connectivity never regresses.
  if (usingOffscreen) {
    // Inbound frames + connection-state relays arrive via the TOP-LEVEL listener
    // registered above (cold-wake-safe) — so we do NOT subscribe dispatchInbound
    // on the proxy here. The proxy owns only ensure-doc + (idempotent) connect +
    // pill state. Re-running this on every wake is a no-op in the offscreen host.
    const proxy = new OffscreenBridgeProxy({
      deviceId: cfg.deviceId,
      deviceName: cfg.deviceName,
      agentVersion: agentVersion(),
      chrome,
    })
    activeBridge = proxy
    proxy.connect(serverUrl, pat)
  } else {
    const client = new DirectBridgeClient({
      deviceId: cfg.deviceId,
      deviceName: cfg.deviceName,
      agentVersion: agentVersion(),
    })
    bridgeUnsub = client.onMessage(dispatchInbound)
    activeBridge = client
    client.connect(serverUrl, pat)
  }
}

/** Tear down the active transport (used by unpair). */
function disconnectActive(): void {
  bridgeUnsub?.()
  bridgeUnsub = null
  try {
    activeBridge?.disconnect()
  } catch {
    // ignore
  }
  activeBridge = null
}

/**
 * True iff the active transport is connected. Recognises all three transports —
 * the in-SW DirectBridgeClient, the offscreen-hosted proxy, and the
 * Native-Messaging bridge — since the desktop path connects via NativeBridge on
 * startup and the sidepanel pill / external `ping` read this.
 */
function isConnected(): boolean {
  return (
    (activeBridge instanceof DirectBridgeClient ||
      activeBridge instanceof OffscreenBridgeProxy ||
      activeBridge instanceof NativeBridge) &&
    activeBridge.connected
  )
}

const tabGroupManager = new TabGroupManager(chrome, sendUp)
// Fire-and-forget rehydrate; subsequent reads await internal #loadGroups
// which handles the race correctly.
tabGroupManager.load().catch(e => {
  console.error('[mateclaw][sw] TabGroupManager.load failed', e)
})

// DebuggerManager: shared CDP attach/detach + send across all CDP-using
// handlers (click/type/scroll/move_mouse). The instance lives here so
// SW restarts re-create it cleanly.
const debuggerManager = new DebuggerManager(chrome)

const resolver = new TabRefResolver({
  tabGroupManager,
  chrome,
  subject: SUBJECT,
})

// -----------------------------------------------------------------
// Real handler registry (Wave 3 task 0 — swapped in from B3-B8 stubs).
//
// All handlers built on Wave-2 deliverables:
//   navigate   — chrome.tabs.update + webNavigation race
//   click      — CDP Input.dispatchMouseEvent press/release with hold
//   type       — CDP Input.dispatchKeyEvent keyDown+char+keyUp per char
//   press_key  — CDP Input.dispatchKeyEvent keyDown+keyUp for shortcuts
//   scroll     — CDP Input.dispatchMouseEvent(type=mouseWheel) segmented
//   move_mouse — WindMouse waypoints over CDP Input.dispatchMouseEvent
//   wait       — three strategies (time / load_state / network_idle)
//
// Per-tab cursor state shared by move_mouse so consecutive moves continue
// from the previous arrival point.
// -----------------------------------------------------------------

const cursorState = new Map<number, Point>()
const regionRegistry = new RegionRegistry()
const baseScrollHandler = scrollHandler({ debugger: debuggerManager, chrome })

const handlers: ActionHandlers = {
  navigate:   navigateHandler(chrome),
  click:      clickHandler({ debugger: debuggerManager, chrome }),
  type:       typeHandler({ debugger: debuggerManager, chrome, clearFirst: true }),
  press_key:  pressKeyHandler({ debugger: debuggerManager }),
  scroll:     baseScrollHandler,
  scroll_region: scrollRegionHandler({ regions: regionRegistry, scroll: baseScrollHandler, chrome }),
  register_region: registerRegionHandler({ regions: regionRegistry }),
  detect_region: detectRegionHandler({ regions: regionRegistry, chrome }),
  extract_region: extractRegionHandler({ regions: regionRegistry, chrome }),
  open_author_from_comment: openAuthorFromCommentHandler({ chrome, tabGroupManager, subject: SUBJECT, debugger: debuggerManager }),
  click_profile_action: clickProfileActionHandler({ chrome }),
  type_dm_draft: typeDmDraftHandler({ debugger: debuggerManager, chrome }),
  close_tab: closeTabHandler({ chrome }),
  move_mouse: moveMouseHandler({
    debugger: debuggerManager,
    cursorState,
    chrome,
    initialCursorPosition: tabId => viewportCenterFromDebugger(debuggerManager, tabId),
  }),
  wait:       waitHandler({ chrome }),
  douyin_comment_network: douyinCommentNetworkHandler({ debugger: debuggerManager, chrome }),
  douyin_search: douyinSearchHandler({ chrome }),
  douyin_open_video: douyinOpenVideoHandler({ chrome }),
  douyin_ui: douyinUiHandler({ chrome }),
}

const executor = new ActionExecutor(handlers)

/**
 * Map of in-flight ActionExecutor runs keyed by request msg_id. Phase 2-1
 * scaffolds it — the router calls `.abort()` on cancel — but no handler
 * passes the AbortSignal through to its work yet. Wave 3 will wire the
 * signal into navigate/click/etc. for true mid-action cancellation.
 */
const inflight = new Map<string, AbortController>()

const router = new ActionRouter({
  resolver,
  executor,
  sendUp,
  inflight,
  tabGroupManager,
  subject: SUBJECT,
})

const snapshotHandler = new SnapshotRequestHandler({
  resolver,
  sendUp,
  // Share the CDP session manager so snapshots try a CDP-native a11y
  // extraction first (Chrome's own accessibility tree), falling back to the
  // injected-JS DOM walker on any failure.
  debuggerManager,
})

const screenshotCaptureHandler = new ScreenshotCaptureHandler({
  resolver,
  debuggerManager,
  sendUp,
})

const visualCoordinator = new VisualCoordinator({
  resolver,
  sendUp,
  chrome,
})

// -----------------------------------------------------------------
// Inbound Edge messages — single dispatcher re-subscribed on each transport.
// -----------------------------------------------------------------

/**
 * Synthesize an `indicator.show` envelope for the same tab_ref carried by an
 * inbound work envelope (action.execute / a11y.snapshot.request /
 * screenshot.capture.request) and route it through the VisualCoordinator.
 *
 * <p>The orchestrator only emits `indicator.hide` (on user-stop / cancel) —
 * never `indicator.show` — so without this hook live tasks render with no
 * visual feedback at all. Auto-routing SHOW here is idempotent (the
 * VisualCoordinator's heartbeat-start dedupes), survives content-script
 * navigation (every subsequent action re-mounts overlays on the post-nav
 * page), and matches the official Claude-in-Chrome takeover feel where the
 * cursor + glow + Stop button stay visible for the duration of the agent's
 * activity. HIDE remains driven by stop-click / cancel.
 */
function autoShowIndicators(inbound: EdgeMessage): void {
  const payload = inbound.payload as { tab_ref?: unknown } | undefined
  const tabRef = payload?.tab_ref
  if (tabRef === undefined) return
  visualCoordinator
    .handle(makeEdgeMessage({
      kind: EdgeMessageKind.IndicatorShow,
      traceId: inbound.trace_id,
      payload: { tab_ref: tabRef },
    }))
    .catch(e => {
      console.warn('[mateclaw][sw] auto IndicatorShow failed', e)
    })
}

function dispatchInbound(m: EdgeMessage): void {
  // Auto-show indicators on any inbound work envelope so the user sees the
  // glow + phantom cursor + Stop button whenever the agent is acting on
  // their browser — matches the official "Claude in Chrome" experience.
  // The orchestrator currently only emits indicator.hide (on cancel); without
  // this hook live tasks would never visibly indicate that the agent has
  // taken control. VisualCoordinator's heartbeat-start is idempotent, so
  // re-firing SHOW on every action/snapshot is a no-op after the first.
  if (
    m.kind === EdgeMessageKind.ActionExecute ||
    m.kind === EdgeMessageKind.A11ySnapshotRequest ||
    m.kind === EdgeMessageKind.ScreenshotCaptureRequest
  ) {
    autoShowIndicators(m)
  }

  // Route action.* / indicator.stop_clicked through the ActionRouter.
  if (
    m.kind === EdgeMessageKind.ActionExecute ||
    m.kind === EdgeMessageKind.ActionCancel ||
    m.kind === EdgeMessageKind.IndicatorStopClicked
  ) {
    router.handle(m).catch(e => {
      console.error('[mateclaw][sw] ActionRouter.handle threw', e)
    })
  }

  if (m.kind === EdgeMessageKind.A11ySnapshotRequest) {
    snapshotHandler.handle(m).catch(e => {
      console.error('[mateclaw][sw] SnapshotRequestHandler.handle threw', e)
    })
  }

  if (m.kind === EdgeMessageKind.ScreenshotCaptureRequest) {
    screenshotCaptureHandler.handle(m).catch(e => {
      console.error('[mateclaw][sw] ScreenshotCaptureHandler.handle threw', e)
    })
  }

  if (VisualCoordinator.handles(m.kind)) {
    visualCoordinator.handle(m).catch(e => {
      console.error('[mateclaw][sw] VisualCoordinator.handle threw', e)
    })
  }

  // Forward inbound Edge messages to any active listeners (sidepanel,
  // devtools, etc.) — same behaviour as Phase 1.
  chrome.runtime
    .sendMessage({ kind: 'edge.inbound', message: m })
    .catch(() => {
      // Ignore — no listeners open is normal when sidepanel is closed.
    })
}

// -----------------------------------------------------------------
// Startup: connect to the local Native Messaging host immediately.
//
// In the desktop ("装好即连") path the bridge host is already installed and
// Chrome launches it on demand, so the extension needs no pairing/config to
// come online — it just calls connectNative() at SW load. NativeBridge owns
// reconnect + MV3 keepalive, so a suspended-then-woken SW re-launches the host
// automatically. The direct-WSS transport (connectDirect / DirectBridgeClient /
// OffscreenBridgeProxy) is retained for the paired/Claude-Code path and is
// still reachable via pair/unpair, but is NOT called on startup.
// -----------------------------------------------------------------

try {
  connectNative()
} catch (e) {
  console.error('[mateclaw][sw] startup native connect failed', e)
}

// -----------------------------------------------------------------
// Re-show indicators after page navigation.
//
// A navigation tears down the page's content script — and with it the
// glow border, phantom cursor, and Stop Agent button. The new page's
// `visual-indicator.js` loads at document_idle but starts in the "not
// shown" state until something tells it otherwise; without this hook
// the user sees the overlays flash off and only return when the *next*
// action arrives ("样式掉了，过一会才回来"). We re-fire SHOW for any
// managed tab that was already in the showing state — VisualCoordinator's
// heartbeat-start dedupe makes this a no-op if it never lapsed.
// -----------------------------------------------------------------
chrome.webNavigation.onCompleted.addListener(details => {
  if (details.frameId !== 0) return // top frame only
  const tabId = details.tabId
  if (!visualCoordinator.isShowingOn(tabId)) return
  // Tiny delay so the new page's visual-indicator content script (which
  // runs at document_idle) is in place to receive the message. Without
  // this the SHOW lands before the listener is registered and gets
  // silently dropped.
  setTimeout(() => {
    visualCoordinator
      .handle(makeEdgeMessage({
        kind: EdgeMessageKind.IndicatorShow,
        payload: { tab_ref: tabId },
      }))
      .catch(e => {
        console.warn('[mateclaw][sw] re-SHOW after navigation failed', e)
      })
  }, 200)
})

// -----------------------------------------------------------------
// externally_connectable protocol (§2) — admin UI ↔ extension.
// Every handler validates sender.origin against the whitelist and returns
// true to keep the async sendResponse channel open.
// -----------------------------------------------------------------

type ExternalMsg =
  | { type: 'ping' }
  | { type: 'pair'; pat: string; serverUrl: string; deviceName?: string }
  | { type: 'unpair' }
  | { type: 'reconnect' }

chrome.runtime.onMessageExternal.addListener(
  (
    req: unknown,
    sender: chrome.runtime.MessageSender,
    sendResponse: (r: unknown) => void,
  ): boolean => {
    const origin = sender.origin ?? (sender.url ? safeOrigin(sender.url) : undefined)
    if (!origin || !ALLOWED_EXTERNAL_ORIGINS.has(origin)) {
      console.warn('[mateclaw][sw] rejected external message from origin', origin)
      sendResponse({ ok: false, error: 'origin not allowed' })
      return true
    }

    const msg = req as ExternalMsg
    switch (msg?.type) {
      case 'ping': {
        configStore
          .getConfig()
          .then(cfg => {
            sendResponse({
              alive: true,
              deviceName: cfg.deviceName ?? null,
              connected: isConnected(),
              deviceId: cfg.deviceId,
            })
          })
          .catch(e => sendResponse({ alive: true, error: String(e) }))
        return true
      }
      case 'pair': {
        if (typeof msg.pat !== 'string' || typeof msg.serverUrl !== 'string') {
          sendResponse({ ok: false, error: 'pair requires pat + serverUrl' })
          return true
        }
        configStore
          .setPairing({
            serverUrl: msg.serverUrl,
            pat: msg.pat,
            deviceName: msg.deviceName,
          })
          .then(() => connectDirect(msg.serverUrl, msg.pat))
          .then(() => sendResponse({ ok: true }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      }
      case 'unpair': {
        disconnectActive()
        configStore
          .clearPairing()
          .then(() => sendResponse({ ok: true }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      }
      case 'reconnect': {
        // 发起获客时由前端触发：先唤醒(可能已休眠的)SW，再重连。有配对凭据走 direct WSS，
        // 否则回退 native“装好即连”。前端随后轮询 ping 等 connected:true。
        configStore
          .getConfig()
          .then(cfg => {
            if (cfg.serverUrl && cfg.pat) {
              return connectDirect(cfg.serverUrl, cfg.pat)
            }
            connectNative()
            return undefined
          })
          .then(() => sendResponse({ ok: true, connected: isConnected() }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      }
      default:
        sendResponse({ ok: false, error: 'unknown message type' })
        return true
    }
  },
)

function safeOrigin(url: string): string | undefined {
  try {
    return new URL(url).origin
  } catch {
    return undefined
  }
}

// -----------------------------------------------------------------
// Internal (sidepanel) runtime messages.
//   edge.outbound  — pass a raw EdgeMessage to the active transport (Phase 1).
//   bridge.pair    — sidepanel manual pairing (Save & Connect).
//   bridge.unpair  — sidepanel Disconnect.
//   bridge.status  — sidepanel status poll for the pill.
// -----------------------------------------------------------------

chrome.runtime.onMessage.addListener(
  (req: unknown, sender, sendResponse: (r: unknown) => void) => {
    const r = req as { kind?: string; message?: unknown; serverUrl?: string; pat?: string; deviceName?: string }
    switch (r?.kind) {
      case 'edge.outbound':
        try {
          sendUp(r.message as EdgeMessage)
          sendResponse({ ok: true })
        } catch (e) {
          sendResponse({ ok: false, error: String(e) })
        }
        return true
      case 'bridge.pair':
        if (typeof r.serverUrl !== 'string' || typeof r.pat !== 'string') {
          sendResponse({ ok: false, error: 'bridge.pair requires serverUrl + pat' })
          return true
        }
        configStore
          .setPairing({ serverUrl: r.serverUrl, pat: r.pat, deviceName: r.deviceName })
          .then(() => connectDirect(r.serverUrl as string, r.pat as string))
          .then(() => sendResponse({ ok: true }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      case 'bridge.unpair':
        disconnectActive()
        configStore
          .clearPairing()
          .then(() => sendResponse({ ok: true }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      case 'bridge.status':
        configStore
          .getConfig()
          .then(cfg =>
            sendResponse({
              connected: isConnected(),
              serverUrl: cfg.serverUrl ?? null,
              deviceName: cfg.deviceName ?? null,
              deviceId: cfg.deviceId,
            }),
          )
          .catch(e => sendResponse({ connected: false, error: String(e) }))
        return true
      case 'runtime.region.register': {
        const registration = parseRegionRegistrationMessage(req)
        if (!registration) {
          sendResponse({ ok: false, error: 'runtime.region.register payload was malformed' })
          return true
        }
        try {
          const region = regionRegistry.register(registration)
          sendResponse({ ok: true, regionKey: region.key })
        } catch (e) {
          sendResponse({ ok: false, error: String(e) })
        }
        return true
      }
      case 'runtime.region.clear': {
        const payload = parseRegionClearMessage(req)
        if (!payload) {
          sendResponse({ ok: false, error: 'runtime.region.clear payload was malformed' })
          return true
        }
        if (typeof payload.tabId === 'number' && typeof payload.regionKey === 'string') {
          sendResponse({ ok: regionRegistry.delete(payload.tabId, payload.regionKey) })
        } else if (typeof payload.tabId === 'number') {
          regionRegistry.clearTab(payload.tabId)
          sendResponse({ ok: true })
        } else {
          regionRegistry.clear()
          sendResponse({ ok: true })
        }
        return true
      }
      default:
        break
    }

    const internal = req as { type?: string }
    switch (internal?.type) {
      case 'STOP_AGENT': {
        const tabId = sender.tab?.id
        sendUp(makeEdgeMessage({
          kind: EdgeMessageKind.IndicatorStopClicked,
          payload: typeof tabId === 'number' ? { tab_ref: tabId } : {},
        }))
        sendResponse({ ok: true })
        return true
      }
      case 'STATIC_INDICATOR_HEARTBEAT': {
        const tabId = sender.tab?.id
        if (typeof tabId !== 'number') {
          sendResponse({ ok: false })
          return true
        }
        tabGroupManager
          .isManagedTab(tabId)
          .then(ok => sendResponse({ ok }))
          .catch(() => sendResponse({ ok: false }))
        return true
      }
      case 'DISMISS_STATIC_INDICATOR_FOR_GROUP': {
        const tabId = sender.tab?.id
        if (typeof tabId !== 'number') {
          sendResponse({ ok: false })
          return true
        }
        tabGroupManager
          .dismissStaticIndicatorForTab(tabId)
          .then(() => sendResponse({ ok: true }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      }
      case 'SWITCH_TO_MAIN_TAB': {
        const tabId = sender.tab?.id
        if (typeof tabId !== 'number') {
          sendResponse({ ok: false })
          return true
        }
        tabGroupManager
          .switchToMainTabForTab(tabId)
          .then(() => sendResponse({ ok: true }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      }
      default:
        return undefined
    }
  },
)

// -----------------------------------------------------------------
// Native Messaging — the STARTUP transport (called at SW load above) and also
// exported for the Claude-Code path. Tears down any active bridge subscription,
// builds a fresh NativeBridge (which owns reconnect + MV3 keepalive), wires the
// inbound dispatcher, and connects to the local host.
// -----------------------------------------------------------------

export function connectNative(): void {
  bridgeUnsub?.()
  bridgeUnsub = null
  if (activeBridge && 'disconnect' in activeBridge) {
    try {
      activeBridge.disconnect()
    } catch {
      // ignore
    }
  }
  const nb = new NativeBridge(HOST)
  bridgeUnsub = nb.onMessage(dispatchInbound)
  activeBridge = nb
  nb.connect()
}
