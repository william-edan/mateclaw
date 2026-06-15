import { EdgeMessageKind, makeEdgeMessage, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'
import type { DebuggerManager } from './debugger-manager'
import { extractAxTreeViaCdp } from './cdp-ax-extractor'

export interface SnapshotRequestHandlerDeps {
  resolver: TabRefResolver
  /** Chrome API; injectable for tests. Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
  /** Outbound bridge sender — same shape as ActionRouter's sendUp. */
  sendUp: (msg: EdgeMessage) => void
  /** snapshot_id factory (defaults to crypto.randomUUID). */
  uuid?: () => string
  /** captured_at_ms source (defaults to Date.now). */
  clock?: () => number
  /**
   * Shared CDP session manager. When present AND the request targets the top
   * frame (no explicit `frame_id`), {@link SnapshotRequestHandler.captureSnapshot}
   * tries a CDP-native a11y extraction first (Chrome's own accessibility tree
   * over DevTools Protocol) and falls back to the injected-JS DOM walker on ANY
   * error. When absent the handler behaves exactly as before — JS walker only.
   */
  debuggerManager?: DebuggerManager
}

type SnapshotFilter = 'interactive' | 'all' | 'default'
const DEFAULT_DEPTH = 15
const DEFAULT_MAX_CHARS = 200000
/**
 * DOM-settle defaults. Many SPAs (e.g. Douyin) paint their header/search bar
 * ~300-800ms AFTER the navigation load event, so reading the a11y tree the
 * instant the extractor is injected yields a half-rendered page. Before
 * extracting we wait until the DOM goes quiet (no mutations for
 * SETTLE_QUIET_MS) once `readyState === 'complete'`, or until a hard
 * SETTLE_CAP_MS ceiling — whichever comes first.
 */
const SETTLE_QUIET_MS = 200
const SETTLE_CAP_MS = 2000

/** Tunable knobs for {@link waitForSettle}; both default when omitted. */
export interface SettleOptions {
  /** Quiet window (ms) with no DOM mutations + readyState complete. */
  quietMs?: number
  /** Hard ceiling (ms) regardless of activity. */
  capMs?: number
}

/**
 * The `[quietMs, capMs]` pair appended to the extractor `executeScript` args.
 * Exported so the wiring is unit-testable without serialising the injected
 * func. Clamps to non-negative finite numbers, falling back to the module
 * defaults, so a bogus override can never starve or hang the extractor.
 */
export function settleArgs(opts: SettleOptions = {}): [number, number] {
  const quiet = clampMs(opts.quietMs, SETTLE_QUIET_MS)
  const cap = clampMs(opts.capMs, SETTLE_CAP_MS)
  return [quiet, cap]
}

function clampMs(value: number | undefined, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : fallback
}

/**
 * Resolve once the document is "settled enough" to snapshot:
 *  - `readyState === 'complete'` AND no DOM mutations for a `quietMs` quiet
 *    window (a MutationObserver on documentElement resets a timer per batch), OR
 *  - `capMs` elapses (hard ceiling) — whichever fires first.
 *
 * Always resolves (never rejects) and disconnects the observer + clears timers
 * before resolving, so it is safe to `await` unconditionally inside the page.
 *
 * This is an exact, standalone mirror of the closure folded into the injected
 * extractor func below — kept in module scope ONLY so the timing logic is
 * unit-testable with fake timers (the injected copy is serialised by
 * `chrome.scripting.executeScript` and cannot reference module bindings).
 * Keep the two in sync.
 */
export function waitForSettle(doc: Document, opts: SettleOptions = {}): Promise<void> {
  const [quietMs, capMs] = settleArgs(opts)
  return new Promise<void>(resolve => {
    const root = doc.documentElement
    // No documentElement (degenerate doc) → nothing to observe; resolve now.
    if (!root || typeof MutationObserver === 'undefined') {
      resolve()
      return
    }

    let quietTimer: ReturnType<typeof setTimeout> | undefined
    let capTimer: ReturnType<typeof setTimeout> | undefined
    let observer: MutationObserver | undefined
    let done = false

    const finish = () => {
      if (done) return
      done = true
      if (quietTimer !== undefined) clearTimeout(quietTimer)
      if (capTimer !== undefined) clearTimeout(capTimer)
      observer?.disconnect()
      resolve()
    }

    // Arm/re-arm the quiet timer. Only counts down once the document has
    // finished loading; while still loading we keep waiting (the cap still
    // bounds total time).
    const armQuiet = () => {
      if (quietTimer !== undefined) clearTimeout(quietTimer)
      if (doc.readyState !== 'complete') return
      quietTimer = setTimeout(finish, quietMs)
    }

    observer = new MutationObserver(armQuiet)
    observer.observe(root, { subtree: true, childList: true, attributes: true })

    // Hard ceiling — fires regardless of mutation activity or readyState.
    capTimer = setTimeout(finish, capMs)

    // If we are already complete, start the quiet countdown immediately;
    // otherwise wait for the window `load` event (load doesn't fire on the
    // document node), then begin counting. The observer also re-checks
    // readyState on every batch, so a late transition to complete is caught
    // even if the load listener is unavailable.
    if (doc.readyState === 'complete') {
      armQuiet()
    } else {
      doc.defaultView?.addEventListener?.('load', armQuiet, { once: true })
    }
  })
}

interface SnapshotRequestPayload {
  tab_ref: TabRef
  filter: SnapshotFilter
  depth: number
  max_chars: number
  ref_id?: string
  frame_id?: number
  /** Optional DOM-settle timing overrides; defaults applied by settleArgs(). */
  settle?: SettleOptions
}

interface SnapshotResult {
  tree: string
  viewport: { w: number; h: number }
  /** Live `location.href` at extraction time. Empty if unavailable. */
  url: string
  /** Live `document.title` at extraction time. Empty if unavailable. */
  title: string
}

/**
 * Handles inbound a11y.snapshot.request envelopes.
 */
export class SnapshotRequestHandler {
  constructor(private readonly deps: SnapshotRequestHandlerDeps) {}

  async handle(msg: EdgeMessage): Promise<void> {
    const req = parseSnapshotRequest(msg.payload)
    if (!req) {
      this.sendFailure(msg, -1, 'SNAPSHOT_FAILED', 'a11y.snapshot.request payload was malformed', false)
      return
    }

    const snapshotId = this.uuid()
    const capturedAtMs = this.clock()

    let tabId: number | null
    try {
      tabId = await this.deps.resolver.resolve(req.tab_ref)
    } catch (err) {
      this.sendFailure(
        msg,
        -1,
        'NO_TARGET_TAB',
        `tab_ref resolution threw: ${errorMessage(err)}`,
        true,
        snapshotId,
        capturedAtMs,
      )
      return
    }

    if (tabId === null) {
      this.sendFailure(
        msg,
        -1,
        'NO_TARGET_TAB',
        `could not resolve tab_ref=${JSON.stringify(req.tab_ref)}`,
        false,
        snapshotId,
        capturedAtMs,
      )
      return
    }

    let snapshot: SnapshotResult
    try {
      snapshot = await this.captureSnapshot(tabId, req)
    } catch (err) {
      this.sendFailure(
        msg,
        tabId,
        'SNAPSHOT_FAILED',
        errorMessage(err),
        true,
        snapshotId,
        capturedAtMs,
      )
      return
    }

    logA11ySnapshotForDebug(tabId, snapshotId, req, snapshot)
    this.sendResponse(msg, {
      snapshot_id: snapshotId,
      captured_at_ms: capturedAtMs,
      tab_ref: tabId,
      tree: snapshot.tree,
      viewport: snapshot.viewport,
      url: snapshot.url,
      title: snapshot.title,
    })
  }

  /**
   * Capture the page a11y tree. CDP-first, JS-fallback:
   *
   *  1. If a {@link DebuggerManager} is wired AND the request targets the top
   *     frame (no explicit `frame_id` — CDP getFullAXTree is whole-page/top
   *     frame), try {@link captureSnapshotViaCdp}: Chrome's own accessibility
   *     tree over DevTools Protocol. This avoids injecting a content script and
   *     re-deriving ARIA roles in JS.
   *  2. On ANY failure of the CDP path — not attachable, CDP command rejected,
   *     empty/degenerate tree — silently fall back to the injected-JS DOM
   *     walker ({@link captureSnapshotViaInjectedJs}), unchanged. The fallback
   *     is also the only path for explicit child-frame requests.
   *
   * Both paths return the SAME {@link SnapshotResult} shape (tree text +
   * viewport + url + title) and both honour the DOM-settle wait.
   */
  private async captureSnapshot(tabId: number, req: SnapshotRequestPayload): Promise<SnapshotResult> {
    const manager = this.deps.debuggerManager
    // CDP extraction is top-frame only (getFullAXTree is whole-page). For an
    // explicit child-frame request, or with no DebuggerManager wired, go
    // straight to the JS walker which handles per-frame extraction.
    if (manager && req.frame_id === undefined) {
      try {
        return await this.captureSnapshotViaCdp(manager, tabId, req)
      } catch {
        // Any CDP failure (not attached, command rejected, empty tree) → fall
        // through to the injected-JS walker below, unchanged.
      }
    }
    return this.captureSnapshotViaInjectedJs(tabId, req)
  }

  /**
   * CDP-native path. Attaches the shared debugger session (idempotent), runs
   * the in-page DOM-settle wait + reads url/title/viewport via a tiny injected
   * func (so SPAs that paint late aren't read half-rendered, same as the JS
   * walker), then extracts the a11y tree from Chrome's accessibility engine.
   * Throws on any CDP failure so {@link captureSnapshot} can fall back.
   */
  private async captureSnapshotViaCdp(
    manager: DebuggerManager,
    tabId: number,
    req: SnapshotRequestPayload,
  ): Promise<SnapshotResult> {
    await manager.attach(tabId)
    try {
      // Settle + metadata first: this waits for the SPA to go quiet (same timing
      // contract as the JS walker) and returns url/title/viewport. Reading the AX
      // tree only after the page settles avoids a half-rendered capture.
      const meta = await this.settleAndReadMeta(tabId, req)
      const tree = await extractAxTreeViaCdp(manager, tabId, req.filter, req.max_chars)
      return { tree, viewport: meta.viewport, url: meta.url, title: meta.title }
    } finally {
      // A snapshot is a read-only operation. Do not keep CDP attached after it:
      // if the MV3 service worker is suspended between observe and the next
      // action, Chrome can otherwise retain a debugger binding that the fresh
      // worker no longer has in memory, causing "Another debugger is already
      // attached" on the following click/hover/type.
      await manager.detach(tabId)
    }
  }

  /**
   * Inject a minimal func that performs the DOM-settle wait (an inline copy of
   * {@link waitForSettle}, identical to the JS walker's) and returns the live
   * url/title/viewport — but NOT the tree (the CDP path sources that). Used by
   * the CDP path so it preserves the settle wait + metadata without re-running
   * the full a11y DOM walk.
   */
  private async settleAndReadMeta(
    tabId: number,
    req: SnapshotRequestPayload,
  ): Promise<{ viewport: { w: number; h: number }; url: string; title: string }> {
    const chrome = this.deps.chrome ?? globalThis.chrome
    const [settleQuietMs, settleCapMs] = settleArgs(req.settle)
    const results = await chrome.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: async (quietMs, capMs) => {
        await new Promise<void>(resolve => {
          const root = document.documentElement
          if (!root || typeof MutationObserver === 'undefined') { resolve(); return }
          const quiet = typeof quietMs === 'number' ? quietMs : 200
          const cap = typeof capMs === 'number' ? capMs : 2000
          let quietTimer: ReturnType<typeof setTimeout> | undefined
          let capTimer: ReturnType<typeof setTimeout> | undefined
          let done = false
          const observer = new MutationObserver(() => armQuiet())
          const finish = () => {
            if (done) return
            done = true
            if (quietTimer !== undefined) clearTimeout(quietTimer)
            if (capTimer !== undefined) clearTimeout(capTimer)
            observer.disconnect()
            resolve()
          }
          function armQuiet() {
            if (quietTimer !== undefined) clearTimeout(quietTimer)
            if (document.readyState !== 'complete') return
            quietTimer = setTimeout(finish, quiet)
          }
          observer.observe(root, { subtree: true, childList: true, attributes: true })
          capTimer = setTimeout(finish, cap)
          if (document.readyState === 'complete') {
            armQuiet()
          } else {
            window.addEventListener('load', () => armQuiet(), { once: true })
          }
        })
        const url = (() => {
          try { return location.href } catch { return '' }
        })()
        const title = (() => {
          try { return document.title || '' } catch { return '' }
        })()
        const vw = window.innerWidth || document.documentElement?.clientWidth || 1280
        const vh = window.innerHeight || document.documentElement?.clientHeight || 800
        return { viewport: { w: vw, h: vh }, url, title }
      },
      args: [settleQuietMs, settleCapMs],
    })
    const first = results[0]?.result as
      | { viewport?: { w?: unknown; h?: unknown }; url?: unknown; title?: unknown }
      | undefined
    const w = typeof first?.viewport?.w === 'number' ? first.viewport.w : 1280
    const h = typeof first?.viewport?.h === 'number' ? first.viewport.h : 800
    return {
      viewport: { w, h },
      url: typeof first?.url === 'string' ? first.url : '',
      title: typeof first?.title === 'string' ? first.title : '',
    }
  }

  /**
   * Injected-JS extraction path (the original, unchanged behaviour). Used as
   * the fallback for the CDP path and as the only path for child-frame requests
   * or when no DebuggerManager is wired.
   */
  private async captureSnapshotViaInjectedJs(tabId: number, req: SnapshotRequestPayload): Promise<SnapshotResult> {
    const chrome = this.deps.chrome ?? globalThis.chrome
    const target = req.frame_id === undefined
      ? { tabId, allFrames: false }
      : { tabId, frameIds: [req.frame_id] }
    // Ensure window.__mateclaw_a11y_tree exists before extracting. The manifest
    // content script may not have run yet — document_idle races the navigate
    // load event, the tab may have started as about:blank, or it predates the
    // extension. Programmatic injection is idempotent (the content script no-ops
    // if already present) and guarantees the extractor is available, instead of
    // failing with "not available" + an empty/zero-viewport snapshot.
    try {
      await chrome.scripting.executeScript({ target, files: ['content/a11y-tree.js'] })
    } catch {
      // best-effort — the func below surfaces a clear error if still missing
    }
    const [settleQuietMs, settleCapMs] = settleArgs(req.settle)
    const results = await chrome.scripting.executeScript({
      target,
      func: async (filter, depth, maxChars, refId, frameId, quietMs, capMs) => {
        // The arg types come back loose (string|number|undefined) — the
        // call-site contract guarantees correct concrete types; assert.
        if (typeof frameId === 'number') {
          ;(window as Window & { __mateclaw_a11y_frame_id?: number }).__mateclaw_a11y_frame_id = frameId
        }
        // ---- DOM settle wait (runs in the page) -------------------------
        // Block extraction until the SPA stops mutating (quiet window) once
        // it has finished loading, or a hard cap elapses — so observe doesn't
        // read a half-rendered page right after navigate. This is an inline
        // copy of waitForSettle() in the SW module (kept in sync for tests);
        // it CANNOT reference module scope because executeScript serialises
        // this func into the page. Never throws — always resolves.
        await new Promise<void>(resolve => {
          const root = document.documentElement
          if (!root || typeof MutationObserver === 'undefined') { resolve(); return }
          const quiet = typeof quietMs === 'number' ? quietMs : 200
          const cap = typeof capMs === 'number' ? capMs : 2000
          let quietTimer: ReturnType<typeof setTimeout> | undefined
          let capTimer: ReturnType<typeof setTimeout> | undefined
          let done = false
          const observer = new MutationObserver(() => armQuiet())
          const finish = () => {
            if (done) return
            done = true
            if (quietTimer !== undefined) clearTimeout(quietTimer)
            if (capTimer !== undefined) clearTimeout(capTimer)
            observer.disconnect()
            resolve()
          }
          function armQuiet() {
            if (quietTimer !== undefined) clearTimeout(quietTimer)
            if (document.readyState !== 'complete') return
            quietTimer = setTimeout(finish, quiet)
          }
          observer.observe(root, { subtree: true, childList: true, attributes: true })
          capTimer = setTimeout(finish, cap)
          if (document.readyState === 'complete') {
            armQuiet()
          } else {
            window.addEventListener('load', () => armQuiet(), { once: true })
          }
        })
        // -----------------------------------------------------------------
        const requestedRefId = typeof refId === 'string' ? refId : undefined
        const tree = window.__mateclaw_a11y_tree?.(
          filter as 'interactive' | 'all' | 'default',
          depth as number,
          maxChars as number,
          requestedRefId,
        )
        if (typeof tree !== 'string') {
          throw new Error('window.__mateclaw_a11y_tree is not available')
        }
        // URL + Title are returned as SEPARATE fields beside the tree (not
        // embedded in the tree text) so the tree stays pure a11y for the
        // server-side grounding engines, and the orchestrator can surface
        // URL / Title to the LLM in a dedicated JSON slot. This matches the
        // official Claude-in-Chrome extension architecture.
        const url = (() => {
          try { return location.href } catch { return '' }
        })()
        const title = (() => {
          try { return document.title || '' } catch { return '' }
        })()
        // innerWidth/Height can be 0 on a freshly-created tab whose renderer
        // hasn't laid out yet (observe right after navigate). Fall back to the
        // document client size, then a sane default, so the server's positive-
        // viewport validation doesn't reject an otherwise-valid tree.
        const vw = window.innerWidth || document.documentElement?.clientWidth || 1280
        const vh = window.innerHeight || document.documentElement?.clientHeight || 800
        return {
          tree,
          viewport: { w: vw, h: vh },
          url,
          title,
        }
      },
      args: [req.filter, req.depth, req.max_chars, req.ref_id ?? null, req.frame_id ?? null, settleQuietMs, settleCapMs],
    })
    const first = results[0]?.result
    if (!isSnapshotResult(first)) {
      throw new Error('a11y snapshot injection returned an invalid result')
    }
    // Normalise missing url/title (e.g. an older cached extractor that doesn't
    // emit them yet) to empty strings so downstream consumers see a stable
    // shape and never see `undefined`.
    return {
      tree: first.tree,
      viewport: first.viewport,
      url: typeof first.url === 'string' ? first.url : '',
      title: typeof first.title === 'string' ? first.title : '',
    }
  }

  private sendResponse(inbound: EdgeMessage, payload: Record<string, unknown>): void {
    this.deps.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.A11ySnapshotResponse,
      traceId: inbound.trace_id,
      inReplyTo: inbound.msg_id,
      payload,
    }))
  }

  private sendFailure(
    inbound: EdgeMessage,
    tabId: number,
    code: 'NO_TARGET_TAB' | 'SNAPSHOT_FAILED',
    message: string,
    retryable: boolean,
    snapshotId = this.uuid(),
    capturedAtMs = this.clock(),
  ): void {
    // The published snapshot response shape is not a Success/Failure union.
    // For typed failures, keep the a11y.snapshot.response envelope and attach
    // an error object beside an empty tree + zero viewport so CP can still
    // correlate freshness by resolved tab id when one exists.
    this.sendResponse(inbound, {
      snapshot_id: snapshotId,
      captured_at_ms: capturedAtMs,
      tab_ref: tabId,
      tree: '',
      viewport: { w: 0, h: 0 },
      url: '',
      title: '',
      error: { code, message, retryable },
    })
  }

  private uuid(): string {
    // crypto.randomUUID requires `this === crypto`; `(x ?? crypto.randomUUID)()`
    // calls it detached → "Illegal invocation". Call it with its receiver.
    return this.deps.uuid ? this.deps.uuid() : crypto.randomUUID()
  }

  private clock(): number {
    return this.deps.clock ? this.deps.clock() : Date.now()
  }
}

function logA11ySnapshotForDebug(
  _tabId: number,
  _snapshotId: string,
  _req: SnapshotRequestPayload,
  _snapshot: SnapshotResult,
): void {
  // Intentionally disabled: snapshot logging is too noisy during Douyin lead tests.
}

function parseSnapshotRequest(payload: unknown): SnapshotRequestPayload | null {
  if (!payload || typeof payload !== 'object') return null
  const p = payload as Record<string, unknown>

  if (p.tab_ref !== 'main' && p.tab_ref !== 'active' && typeof p.tab_ref !== 'number') return null
  if (p.filter !== 'interactive' && p.filter !== 'all' && p.filter !== 'default') return null
  if (p.depth !== undefined && typeof p.depth !== 'number') return null
  if (p.max_chars !== undefined && typeof p.max_chars !== 'number') return null
  if (p.ref_id !== undefined && typeof p.ref_id !== 'string') return null
  if (p.frame_id !== undefined || 'frame_id' in p) {
    if (typeof p.frame_id !== 'number') return null
    if (!Number.isInteger(p.frame_id) || p.frame_id < 0) return null
  }

  return {
    ...p,
    depth: typeof p.depth === 'number' ? p.depth : DEFAULT_DEPTH,
    max_chars: typeof p.max_chars === 'number' ? p.max_chars : DEFAULT_MAX_CHARS,
  } as unknown as SnapshotRequestPayload
}

function isSnapshotResult(value: unknown): value is SnapshotResult {
  if (!value || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  if (typeof v.tree !== 'string') return false
  if (!v.viewport || typeof v.viewport !== 'object') return false
  const viewport = v.viewport as Record<string, unknown>
  if (typeof viewport.w !== 'number' || typeof viewport.h !== 'number') return false
  // url + title are required by the new shape but we tolerate missing values
  // (older extractor results or future shape drift) — captureSnapshot normalises
  // missing to "" before propagating, so the response always carries strings.
  return true
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}
