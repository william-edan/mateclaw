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
import { LocalBridgeClient, LOCAL_BRIDGE_URL } from './local-bridge'
import { OffscreenBridgeProxy } from './offscreen-bridge-proxy'
import {
  OFFSCREEN_DISCONNECTED,
  OFFSCREEN_INBOUND,
  OFFSCREEN_SEND,
  OFFSCREEN_STATE,
  OFFSCREEN_UPSTREAM_STATE,
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

// LOCAL_BRIDGE_URL(含 `/bridge` 路径)从 ./local-bridge 单点导入 —— 不再在此复制字面量,
// 杜绝两份漂移(曾因裸根 `ws://127.0.0.1:18077` 缺 `/bridge` 被 loopback 拒握手,连接从未建立)。

/**
 * chrome.storage.local key for the resident-local feature flag (跨组契约4). When
 * truthy, {@link reconnectByPairing} prefers the resident bridge loopback over
 * the native "装好即连" host (but still after a direct pairing). DEFAULT OFF:
 * absent/false ⇒ the existing direct/native routing — 全系统行为与今天完全一致.
 *
 * Read directly from storage here (rather than via ConfigStore) so this group's
 * change stays inside its allowed files; the value is a simple boolean flag with
 * no other consumers.
 */
const PREFER_LOCAL_BRIDGE_KEY = 'preferLocalBridge'

/**
 * Resolve the {@link PREFER_LOCAL_BRIDGE_KEY} flag (跨组契约4). Fail-safe to
 * false (default off) on any storage error so a read failure can never flip
 * traffic onto the resident bridge.
 */
async function preferLocalBridge(): Promise<boolean> {
  try {
    const got = (await chrome.storage.local.get([PREFER_LOCAL_BRIDGE_KEY])) as Record<
      string,
      unknown
    >
    // 默认 ON(根治:优先走常驻 bridge 本地 IPC)。仅当显式 set preferLocalBridge=false
    // 才回退到 direct/native(应急开关);未设/读失败=ON。
    return got[PREFER_LOCAL_BRIDGE_KEY] !== false
  } catch {
    return true
  }
}

/**
 * 用户主动"断开连接"的持久闩(chrome.storage.local)。常驻架构下扩展会自动重连
 * (顶层 SW 加载 + onStartup/onInstalled + 30s keepalive alarm 全部经 reconnectByPairing),
 * 否则"断开连接"会在 ~30s 内被自动拉回 → 用户反馈"点了没效果"。置位后 reconnectByPairing 直接
 * 跳过自动重连;显式"连接"(pair)/发起获客(reconnect)会清位。持久化以跨 SW 回收/重启生效。
 */
const USER_DISCONNECTED_KEY = 'userDisconnected'

/**
 * 是否处于"用户主动断开"态。
 *
 * 【已弃用为恒 false】桌面端断开改走【后端开关式】(后端禁用该会话,扩展始终连着、可逆)——因为
 * 桌面 SPA 在 Electron 内够不到 Chrome 扩展,旧的"扩展置持久闩+断 loopback+不再重连"在桌面无法被
 * 清除,导致断开后永远"未检测到扩展"(单向陷阱)。这里恒返回 false:① 让扩展始终自动重连,杜绝陷阱;
 * ② 老 install 里残留的 userDisconnected 存量值被忽略,reload 新扩展即自动恢复连接。断开/连接的真正
 * 控制权移到后端({@code /browser/sessions/disconnect|connect} → BrowserSession.disabled)。
 */
async function isUserDisconnected(): Promise<boolean> {
  return false
}

/** 置/清"用户主动断开"闩。 */
async function setUserDisconnected(v: boolean): Promise<void> {
  try {
    await chrome.storage.local.set({ [USER_DISCONNECTED_KEY]: v })
  } catch {
    // ignore — 闩写失败时退化为"自动重连"现状,不致命。
  }
}

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
  'https://ai.devefive.com',
])

// -----------------------------------------------------------------
// Transport: a single active bridge, swappable at runtime (pair/unpair).
// sendUp + dispatchInbound indirect through whatever is active so the
// handler wiring below never needs to know which transport is live.
// -----------------------------------------------------------------

type Bridge = DirectBridgeClient | OffscreenBridgeProxy | NativeBridge | LocalBridgeClient

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
    // actually owns the socket: either the active bridge IS the offscreen proxy
    // (the direct-WSS / Claude-Code offscreen path), or this is a cold SW wake
    // before startup rebuilt the proxy (activeBridge still null) on an
    // offscreen-capable runtime — there the offscreen doc may hold the socket and
    // the reply must not wait for reconnection (stateless on purpose). The in-SW
    // transports own their socket inside the worker, so they send directly:
    //   - NativeBridge       — the desktop native "装好即连" host;
    //   - LocalBridgeClient  — the RESIDENT-LOCAL loopback transport, now held
    //     directly by the SW (no offscreen middle layer) so action.result rides
    //     the SAME proven path as native — see {@link connectResidentLocal};
    //   - the fallback in-SW DirectBridgeClient.
    //
    // Keying this on `usingOffscreen` alone (the old code) silently dropped EVERY
    // outbound frame on the native path: usingOffscreen is true on chrome116+, but
    // connectNative never creates an offscreen document, so action.result/snapshot/
    // heartbeat/HELLO were all posted into the void and swallowed by .catch() —
    // the desktop "connected but no round-trips" failure. The same trap would bite
    // the resident-local path if we routed it through offscreen, so it is now an
    // in-SW transport that hits the `else` (direct send) branch below.
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
    } else if (
      raw.type === OFFSCREEN_STATE ||
      raw.type === OFFSCREEN_DISCONNECTED ||
      // 契约3: BACKEND (upstream) health relay for the resident-local transport.
      // Must be dispatched from this SYNC top-level listener (same as STATE) so a
      // cold-woken SW reliably receives it and the end-to-end `connected` gate
      // (local IPC OPEN ∧ upstream up) stays accurate.
      raw.type === OFFSCREEN_UPSTREAM_STATE
    ) {
      // Connection-state relays drive the sidepanel pill + isConnected().
      if (activeBridge instanceof OffscreenBridgeProxy) activeBridge.ingestRelay(raw)
    }
    return undefined
  })
}

// SW 代码构建标记:随 SW 代码改动手动 bump。附加到 HELLO 的 agent_version,让后端能区分
// "manifest 版本"(reload 不彻底时会虚高)与"实际运行的 SW 代码版本",用于排查 MV3 SW 顽固缓存。
const SW_BUILD = 'e2'
/** Resolve the extension version for the HELLO payload (含 SW 代码构建标记). */
function agentVersion(): string {
  try {
    return chrome.runtime.getManifest().version + '+' + SW_BUILD
  } catch {
    return '0.0.0+' + SW_BUILD
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

/**
 * (Re)connect over the RESIDENT-LOCAL transport (跨组契约1/3/4): the SW holds a
 * {@link LocalBridgeClient} to the resident bridge loopback DIRECTLY — no
 * offscreen middle layer.
 *
 * <p>WHY SW-DIRECT (根治 action 卡住). Connection stability is already owned by
 * the resident bridge holding the backend session at the PROCESS level (a SW
 * recycle drops only the loopback segment, never the bridge↔backend session), so
 * offscreen is not needed here to survive SW suspension. Routing the resident
 * path through offscreen meant inbound action.execute had to relay
 * offscreen→SW and the action.result relay SW→offscreen→loopback — extra hops
 * that left the first "open browser" step waiting on a round-trip that never
 * completed. Holding the socket in the SW puts inbound + outbound on the EXACT
 * SAME path as the proven NativeBridge: loopback frame → {@link LocalBridgeClient}
 * .onMessage → {@link dispatchInbound} → ActionRouter → handler, and the
 * action.result → {@link sendUp} → activeBridge.send → loopback. A SW recycle
 * drops the loopback socket; the LocalBridgeClient's own 100ms→2s reconnect (and
 * the keepalive alarm's reconnectByPairing) re-opens it in milliseconds while the
 * bridge's backend session stays put (契约5 生命周期硬隔离).
 *
 * <p>契约3 (端到端 isConnected) is preserved IN THE SW: LocalBridgeClient.connected
 * is (loopback IPC OPEN ∧ upstream up), where `upstream up` comes from the
 * bridge's {kind:'upstream'} control frame consumed here — never a bare local
 * socket OPEN, so no 假阳性.
 *
 * <p>The offscreen/direct path is untouched and still used by the Claude-Code /
 * direct-WSS pairing flow (connectDirect / reconnectByPairing's serverUrl+pat
 * branch). Only the resident-local channel changed from offscreen-hosted to
 * SW-direct.
 */
function connectResidentLocal(): void {
  // 幂等(去 30s keepalive-alarm 抖动):活着的 SW 上若已持有 LocalBridgeClient,它自带
  // 100ms→2s 自重连 + keepalive 维持 SW 存活——绝不能在每次 alarm 唤醒(30s)时把一条健康
  // 的本地连接 disconnect 掉重建(会瞬断、丢在途 action 帧,甚至打断正在跑的任务)。SW 被
  // 回收后重生时,顶层模块会把 activeBridge 置回 null,那时才需要重建一个新的。
  if (activeBridge instanceof LocalBridgeClient) return

  bridgeUnsub?.()
  bridgeUnsub = null
  if (activeBridge && 'disconnect' in activeBridge) {
    try {
      activeBridge.disconnect()
    } catch {
      // ignore
    }
  }
  const client = new LocalBridgeClient()
  // Same inbound wiring as NativeBridge: every loopback frame → dispatchInbound,
  // which routes action.execute → ActionRouter → handlers and snapshots/visuals.
  bridgeUnsub = client.onMessage(dispatchInbound)
  activeBridge = client
  // No PAT on loopback — the bridge authenticates by Origin (契约2). The
  // `connected` getter gates on (IPC OPEN ∧ upstream up) from the bridge's
  // {kind:'upstream'} frame (契约3), so isConnected() stays end-to-end accurate.
  client.connect(LOCAL_BRIDGE_URL)
}

/**
 * Pairing-aware (re)connect used by startup + the keepalive alarm. Channel
 * priority (跨组契约4): direct 配对 (serverUrl+pat) > resident-local (when the
 * preferLocalBridge flag is on, default ON) > native "装好即连". With the flag
 * explicitly off the resident-local branch is skipped, so this resolves to
 * serverUrl+pat ⇒ direct, else ⇒ native routing. This replaces the old
 * unconditional connectNative(), which would tear a paired direct/offscreen
 * socket and re-handshake natively on every wake.
 *
 * <p>The resident-local channel is now SW-DIRECT (a LocalBridgeClient held by
 * the SW, no offscreen) — see {@link connectResidentLocal} for why. A wake-driven
 * reconnect there rebuilds the LocalBridgeClient (the suspended SW already lost
 * its loopback socket); the bridge's backend session is untouched (契约5), so
 * this is a millisecond loopback re-open, not a backend re-handshake.
 *
 * <p>OFFSCREEN ROUND-TRIP: when the socket lives in the offscreen document it
 * survives SW suspension, so a wake-driven reconnect must NOT blindly tear it
 * down. We re-issue an IDEMPOTENT OFFSCREEN_CONNECT (a fresh proxy handle, no
 * disconnect of the old one): the offscreen host treats a same-creds CONNECT
 * on a live socket as a no-op and re-announces OFFSCREEN_STATE — that relay is
 * the round-trip that refreshes this SW's `connected` flag without churning a
 * still-alive socket. Only when the offscreen host is truly down does its
 * CONNECT rebuild the socket. The in-SW transports (native / fallback
 * DirectBridgeClient) don't survive suspension, so they reconnect normally.
 */
async function reconnectByPairing(): Promise<void> {
  // 用户主动断开闩:抑制【所有】自动重连入口(顶层 SW 加载 / onStartup / onInstalled / 30s
  // keepalive alarm 都经此)。否则常驻架构会在 ~30s 内把刚"断开连接"的会话自动连回 → 用户
  // 反馈"点了没效果"。显式"连接"(pair)/发起获客(reconnect)会先清闩再调本函数,故不受影响。
  if (await isUserDisconnected()) {
    disconnectActive()
    return
  }
  const cfg = await configStore.getConfig()
  if (cfg.serverUrl && cfg.pat) {
    if (usingOffscreen) {
      // Idempotent refresh: rebuild only the lightweight proxy HANDLE and let
      // its CONNECT round-trip confirm/restore the offscreen socket. We do NOT
      // call disconnect on the previous handle — that would post
      // OFFSCREEN_DISCONNECT and kill a socket that is very likely still alive.
      bridgeUnsub?.()
      bridgeUnsub = null
      const proxy = new OffscreenBridgeProxy({
        deviceId: cfg.deviceId,
        deviceName: cfg.deviceName,
        agentVersion: agentVersion(),
        chrome,
      })
      activeBridge = proxy
      proxy.connect(cfg.serverUrl, cfg.pat)
      return
    }
    await connectDirect(cfg.serverUrl, cfg.pat)
    return
  }
  // 跨组契约4: prefer the resident bridge loopback (SW-direct LocalBridgeClient)
  // unless the flag is explicitly disabled. No offscreen requirement — the
  // resident transport is now held directly by the SW (see connectResidentLocal),
  // so it works on any runtime; flag off ⇒ fall through to native "装好即连",
  // preserving the legacy behaviour exactly.
  if (await preferLocalBridge()) {
    connectResidentLocal()
    return
  }
  connectNative()
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
 * True iff the active transport is connected. Recognises all transports — the
 * in-SW DirectBridgeClient, the offscreen-hosted proxy, the Native-Messaging
 * bridge, and the SW-direct resident-local LocalBridgeClient — since the desktop
 * path connects via NativeBridge / LocalBridgeClient on startup and the sidepanel
 * pill / external `ping` read this. For LocalBridgeClient `connected` is the
 * end-to-end gate (loopback IPC OPEN ∧ bridge upstream up, 契约3), so a bare
 * loopback OPEN with the bridge detached from the backend never reads true.
 */
function isConnected(): boolean {
  return (
    (activeBridge instanceof DirectBridgeClient ||
      activeBridge instanceof OffscreenBridgeProxy ||
      activeBridge instanceof NativeBridge ||
      activeBridge instanceof LocalBridgeClient) &&
    activeBridge.connected
  )
}

/**
 * True when the active native bridge has declared itself NON-retryably
 * unavailable (NO_TOKEN). In this state the NativeBridge already runs its OWN
 * sparse ~30s re-probe, so the keepalive alarm must NOT tear it down and rebuild
 * a fresh NativeBridge — doing so would discard the unpaired latch and drop the
 * bridge back into the dense 1s→30s spawn storm on every alarm wake. Returns
 * false for the direct/offscreen transports (they have no such state).
 */
function isNativeUnpaired(): boolean {
  return activeBridge instanceof NativeBridge && activeBridge.unpaired
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
 * Map of in-flight ActionExecutor runs keyed by the action.execute msg_id.
 * handleExecute registers an AbortController on entry and removes it in finally;
 * handleCancel aborts by the msg_id carried on action.cancel's in_reply_to. The
 * signal is threaded through ActionExecutor.run into each handler so side-effect
 * handlers can short-circuit at await boundaries (true mid-action cancellation).
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
  // 后端中转的"断开连接"(桌面 SPA 够不到 Chrome 扩展,只能经 session 下行此指令):
  // 置"用户已断开"闩 + 断开 loopback,且不再自动重连(reconnectByPairing 见闩即跳过)。
  if (m.kind === EdgeMessageKind.ConnectionDisconnect) {
    void setUserDisconnected(true).then(() => disconnectActive())
    return
  }

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
// Startup: connect over the channel the pairing state selects.
//
// A paired install (serverUrl+pat stored) comes online over the direct WSS /
// offscreen transport; an un-paired desktop install uses the local Native
// Messaging host ("装好即连"), which Chrome launches on demand with no config.
// reconnectByPairing() encodes that choice (and the offscreen round-trip), so a
// suspended-then-woken SW reconnects on the SAME channel it was paired on rather
// than always falling back to native. Both transports own reconnect + MV3
// keepalive; pair/unpair still swap channels at runtime.
// -----------------------------------------------------------------

reconnectByPairing().catch(e => {
  console.error('[mateclaw][sw] startup connect failed', e)
})

// -----------------------------------------------------------------
// Chrome 启动 / 扩展安装更新时主动唤醒 SW 发起连接。
//
// MV3 Service Worker 的顶层代码只在 install/update 时执行;Chrome 重启后 SW 不会
// 自动重跑顶层,必须靠事件唤醒。缺了 onStartup 时,重启 Chrome 后要么等 keepalive
// alarm(最多 ~30s)、要么等用户打开 localhost 页面触发 onMessageExternal 才连接
// ——这正是"必须手动打开 /lead-acquisition 才连"的根因。onStartup 让 Chrome 一启动
// 就立即在配对通道上自动连接;onInstalled 覆盖装好/更新后的首次连接。
// -----------------------------------------------------------------
chrome.runtime.onStartup.addListener(() => {
  reconnectByPairing().catch(e => {
    console.error('[mateclaw][sw] onStartup connect failed', e)
  })
})
chrome.runtime.onInstalled.addListener(() => {
  reconnectByPairing().catch(e => {
    console.error('[mateclaw][sw] onInstalled connect failed', e)
  })
})

// -----------------------------------------------------------------
// MV3 keep-alive + auto-reconnect (native path).
//
// A service worker is torn down after ~30s idle, which closes the native port
// (the bridge process exits) and FREEZES any setTimeout-based reconnect — so a
// dropped native connection never recovers on its own. That is exactly the
// "连一次就断、之后不再重连" symptom: no bridge process, no edge activity, and no
// SW logs because the worker is asleep. A periodic alarm is the ONE mechanism
// that reliably WAKES a suspended MV3 worker: each wake re-runs this top-level
// startup (which reconnects), and the handler below re-opens the native port if
// the wake didn't already. Registered synchronously at load so the alarm + its
// handler exist before the worker can suspend.
// -----------------------------------------------------------------
const KEEPALIVE_ALARM = 'mateclaw-edge-keepalive'
try {
  // 0.5 min is the floor on modern Chrome; older builds clamp to 1 min — either
  // way the worker gets woken often enough to keep the native bridge alive.
  chrome.alarms.create(KEEPALIVE_ALARM, { periodInMinutes: 0.5 })
} catch (e) {
  console.error('[mateclaw][sw] alarm create failed', e)
}
chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name !== KEEPALIVE_ALARM) return
  // Unpaired native host (NO_TOKEN): the NativeBridge already owns a sparse ~30s
  // re-probe and the unpaired latch. Rebuilding it here on every wake would
  // discard that state and restart the dense spawn storm — so leave it be and
  // let its own probe detect when the user configures the token. (Contract §2.)
  if (isNativeUnpaired()) return
  if (!isConnected()) {
    // Reconnect on the paired channel (not always native). When the socket is
    // offscreen-hosted, reconnectByPairing re-issues an idempotent CONNECT whose
    // OFFSCREEN_STATE round-trip refreshes `connected` WITHOUT tearing a socket
    // that may still be alive — isConnected() can read false on a cold wake
    // before the proxy handle is rebuilt.
    reconnectByPairing().catch(e => {
      console.error('[mateclaw][sw] alarm reconnect failed', e)
    })
  }
})

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
              // 未配对(NO_TOKEN)态:native host 起来了但没 token,前端据此显示“未配对/
              // 未授权”并停止 connected:true 的轮询,而不是一直空转等连接。
              unpaired: isNativeUnpaired(),
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
          .then(() => setUserDisconnected(false))
          .then(() => connectDirect(msg.serverUrl, msg.pat))
          .then(() => sendResponse({ ok: true }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      }
      case 'unpair': {
        // 置"用户主动断开"闩,再断开当前连接。闩会让 reconnectByPairing(顶层加载/onStartup/
        // onInstalled/30s alarm)全部跳过自动重连 —— 否则常驻架构 ~30s 内把会话拉回,断开"没效果"。
        setUserDisconnected(true)
          .then(() => {
            disconnectActive()
          })
          .then(() => configStore.clearPairing())
          .then(() => sendResponse({ ok: true }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      }
      case 'reconnect': {
        // 发起获客时由前端触发：先唤醒(可能已休眠的)SW，再重连。reconnectByPairing 按配对状态
        // 选通道(有 serverUrl+pat 走 direct WSS/offscreen,否则回退 native“装好即连”),offscreen
        // 路走幂等 CONNECT 往返、不拆仍活着的 socket。前端随后轮询 ping 等 connected:true。
        // 显式重连(发起获客时由前端触发):先清"用户主动断开"闩,使自动重连恢复。
        setUserDisconnected(false)
          .then(() => reconnectByPairing())
          .then(() => sendResponse({ ok: true, connected: isConnected(), unpaired: isNativeUnpaired() }))
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
              unpaired: isNativeUnpaired(),
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
