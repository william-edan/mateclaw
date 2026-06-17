import type { MoveMouseParams } from '../types'
import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DebuggerManager } from '../../debugger-manager'
import { SessionDetachedError } from '../../debugger-manager'
import { generate, type Point } from '../../../lib/windmouse'
import { DOM_ONLY_NO_CDP_FALLBACK } from './debug-flags'

/**
 * Default async sleeper. Resolves after `ms` real milliseconds.
 *
 * Tests inject their own stub so they can assert on the sleep durations
 * without sitting through real timing — see `move_mouse.test.ts`.
 */
function defaultSleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms))
}

/**
 * Minimum wall-clock gap (ms) between two consecutive `INDICATOR_CURSOR`
 * messages pushed to the visual phantom cursor. WindMouse can emit dozens of
 * sub-pixel waypoints on a long haul; relaying every single one would flood
 * chrome.tabs.sendMessage. 16ms (~60fps) keeps the glide perfectly smooth
 * while bounding the message rate — the content-script's 180ms CSS transition
 * already interpolates between whatever positions actually arrive.
 *
 * The FINAL waypoint is always emitted regardless of this throttle so the
 * phantom lands exactly on target.
 */
const DEFAULT_CURSOR_EMIT_MIN_INTERVAL_MS = 16

export interface MoveMouseHandlerDeps {
  /**
   * The DebuggerManager owning the CDP session for this tab. The handler
   * calls `attach(tabId)` before any send (idempotent in B1) and then
   * dispatches every waypoint via `send(tabId, 'Input.dispatchMouseEvent', …)`.
   */
  debugger: DebuggerManager
  /** Injectable RNG passed through to WindMouse. Defaults to Math.random. */
  random?: () => number
  /** Injectable clock — defaults to Date.now (for elapsed bookkeeping). */
  clock?: () => number
  /** Async sleeper between waypoints — defaults to setTimeout-based. Tests stub this. */
  sleep?: (ms: number) => Promise<void>
  /**
   * Tracks the current cursor position per tab so consecutive moves
   * continue from where the previous one ended. Persisted in-memory only
   * (the SW global). Phase 2 keeps this in the handler closure; later
   * phases may promote it onto a session-scoped state object.
   */
  cursorState?: Map<number, Point>
  /**
   * Initial visual/real cursor position for a tab that has no cursorState yet.
   * The production service worker resolves this from the page viewport center,
   * matching Claude's "spawn near the middle of the page" feel. Tests can
   * inject a deterministic point. Falls back to {640,400}.
   */
  initialCursorPosition?: (tabId: number) => Promise<Point>
  /**
   * Chrome API used to drive the VISUAL phantom cursor along the WindMouse
   * path. As each waypoint is dispatched to the real CDP mouse, the handler
   * also fires `chrome.tabs.sendMessage(tabId, { type: 'INDICATOR_CURSOR', x, y })`
   * so the on-page overlay glides the same human-like path the real pointer
   * takes — mirroring the official "Claude in Chrome" extension, whose
   * `dispatchMouseEvent` couples every `mouseMoved` with an `UPDATE_PHANTOM_CURSOR`.
   *
   * Injectable so tests can assert the relayed positions without a real
   * chrome runtime. When omitted (and `globalThis.chrome` is unavailable, e.g.
   * unit tests) the visual relay is silently skipped — the real CDP path is
   * unaffected.
   */
  chrome?: typeof globalThis.chrome
  /**
   * Minimum gap (ms, measured against WindMouse waypoint `t` timestamps)
   * between two relayed `INDICATOR_CURSOR` messages. Defaults to
   * {@link DEFAULT_CURSOR_EMIT_MIN_INTERVAL_MS}. The final waypoint always
   * emits regardless. Set to 0 to relay every waypoint.
   */
  cursorEmitMinIntervalMs?: number
}

/**
 * `move_mouse` action handler — Phase 2 task B7.
 *
 * Consumes the WindMouse library (B9) to generate an organic, jittered
 * path between the current cursor position and the requested target,
 * then dispatches each interior waypoint as a CDP `Input.dispatchMouseEvent`
 * of type `mouseMoved`. Inter-waypoint timing follows the deltas WindMouse
 * embeds in each `Waypoint.t` (ms since path start), so the cursor's
 * apparent velocity follows the same log-normal-jittered curve as the
 * spatial path.
 *
 * Flow:
 *   1. `debugger.attach(tabId)` (idempotent — safe to call on every action).
 *   2. Resolve `from` from `cursorState`; default to the viewport center on
 *      the first move per tab so the cursor does not streak in from (0,0).
 *   3. Generate waypoints via `windmouse.generate(from, to, …)`.
 *   4. For each waypoint **after the first** (the cursor is already at
 *      `from`, so dispatching it would be redundant):
 *        a. `sleep(waypoint.t - prevWaypoint.t)` ms.
 *        b. Relay the waypoint to the VISUAL phantom cursor via
 *           `chrome.tabs.sendMessage(tabId, { type: 'INDICATOR_CURSOR', x, y })`
 *           (throttled to ~60fps; the final waypoint always emits). This makes
 *           the on-page overlay glide the same path the real pointer travels,
 *           matching the official extension's motion feel. Fire-and-forget —
 *           a missing/asleep content script never blocks the real CDP path.
 *        c. `debugger.send(tabId, 'Input.dispatchMouseEvent',
 *                          { type: 'mouseMoved', x, y, button: 'none' })`.
 *   5. Update `cursorState[tabId] = { x: params.x, y: params.y }`.
 *   6. Return Success — payload carries `waypoints` (total count, including
 *      the skipped starting point) and `arrived_at_ms` (clock at completion).
 *
 * Errors:
 *   - `SessionDetachedError` from `debugger.send` (the tab was closed, the
 *     user opened DevTools, etc.) is rethrown as
 *     `ActionFailureError('SESSION_DETACHED', …, retryable=false)`. The
 *     ActionExecutor unwraps the typed code onto the wire.
 *   - Anything else propagates and lands as `HANDLER_ERROR` upstream.
 *
 * Deadlines: this handler does **not** race against `deadlineMs`. WindMouse
 * paths are bounded (≤ 800ms by default) and CDP sends complete in single
 * digits of ms, so the worst case is well within typical deadlines. The
 * Control Plane still enforces the deadline at `ActionExecutionService` —
 * a timeout there triggers `action.cancel` which detaches and bubbles
 * `SESSION_DETACHED` up through us naturally.
 */
export const moveMouseHandler = (deps: MoveMouseHandlerDeps): ActionHandler<MoveMouseParams> => {
  const random = deps.random ?? Math.random
  const clock = deps.clock ?? Date.now
  const sleep = deps.sleep ?? defaultSleep
  const cursorState = deps.cursorState ?? new Map<number, Point>()
  const emitMinIntervalMs = deps.cursorEmitMinIntervalMs ?? DEFAULT_CURSOR_EMIT_MIN_INTERVAL_MS
  const initialCursorPosition = deps.initialCursorPosition ?? defaultInitialCursorPosition

  return async (tabId, params, _deadlineMs) => {
    // 【临时调试 · DOM_ONLY】纯 CDP 鼠标移动(hover / park / move_mouse)在后台对 DOM 操作
    // 没有意义:elementFromPoint 用的是传入坐标,合成 hover 由 douyin_ui 自己派发 pointerenter,
    // 都不依赖真实光标位置。故 DOM-only 模式下直接 no-op 成功返回(保持 requireOk 通过),
    // 避免在可见窗口里看到鼠标乱移动(park 到评论区 / hover 到筛选)。见 debug-flags.ts。
    if (DOM_ONLY_NO_CDP_FALLBACK) {
      cursorState.set(tabId, { x: params.x, y: params.y })
      return {
        ok: true,
        elapsed_ms: 0,
        payload: { mode: 'dom_only_noop', x: params.x, y: params.y },
      }
    }

    await deps.debugger.attach(tabId)

    const from = cursorState.get(tabId) ?? await safeInitialCursorPosition(initialCursorPosition, tabId)
    const to: Point = { x: params.x, y: params.y }

    const waypoints = generate(from, to, {
      profile: params.profile ?? 'natural',
      random,
    })

    // Resolve the chrome runtime once. The visual relay is best-effort: if
    // neither an injected chrome nor a global one is present (unit tests),
    // `emitCursor` becomes a no-op and only the real CDP path runs.
    const chromeApi = deps.chrome ?? (typeof globalThis !== 'undefined' ? globalThis.chrome : undefined)
    // Throttle bookkeeping: `t` of the last waypoint we relayed to the
    // phantom cursor. Seeded to -Infinity so the first interior waypoint
    // always emits.
    let lastEmittedT = Number.NEGATIVE_INFINITY

    /**
     * Push one position to the on-page phantom cursor. Fire-and-forget:
     * the returned promise is intentionally not awaited and its rejection
     * is swallowed (a closed channel / absent content script must never
     * derail the real mouse path). Mirrors the official extension's
     * `chrome.tabs.sendMessage(...).catch(()=>{})`.
     */
    const emitCursor = (x: number, y: number): void => {
      const tabs = chromeApi?.tabs
      if (!tabs || typeof tabs.sendMessage !== 'function') return
      try {
        const ret = tabs.sendMessage(tabId, { type: 'INDICATOR_CURSOR', x, y }) as
          | Promise<unknown>
          | undefined
        // MV3 returns a Promise; older shims may return void. Guard before .catch.
        if (ret && typeof (ret as Promise<unknown>).catch === 'function') {
          ;(ret as Promise<unknown>).catch(() => {})
        }
      } catch {
        // sendMessage can throw synchronously if the SW is tearing down.
        // Nothing actionable — the real CDP dispatch below is what matters.
      }
    }

    // Skip the very first waypoint — it equals `from` and the cursor is
    // already there. Walking from index 1 also lets us read `t` deltas
    // cleanly: each dispatch is preceded by a sleep equal to (current
    // waypoint's t) - (previous waypoint's t).
    for (let i = 1; i < waypoints.length; i++) {
      const prev = waypoints[i - 1]!
      const wp = waypoints[i]!
      const gap = Math.max(0, wp.t - prev.t)

      await sleep(gap)

      // Drive the VISUAL phantom cursor along the SAME path as the real
      // pointer. Emit when enough path-time has elapsed since the last
      // relay (≈60fps), and ALWAYS emit the final waypoint so the overlay
      // lands exactly on target. The emit precedes the CDP dispatch (visual
      // leads fractionally), matching the official choreography.
      const isLast = i === waypoints.length - 1
      if (isLast || wp.t - lastEmittedT >= emitMinIntervalMs) {
        emitCursor(wp.x, wp.y)
        lastEmittedT = wp.t
      }

      try {
        await deps.debugger.send(tabId, 'Input.dispatchMouseEvent', {
          type: 'mouseMoved',
          x: wp.x,
          y: wp.y,
          button: 'none',
        })
      } catch (err) {
        if (err instanceof SessionDetachedError) {
          throw new ActionFailureError(
            'SESSION_DETACHED',
            err.message,
            false,
          )
        }
        throw err
      }
    }

    cursorState.set(tabId, { x: params.x, y: params.y })

    return {
      ok: true,
      elapsed_ms: 0, // ActionExecutor overwrites with wall-clock measurement
      payload: {
        arrived_at_ms: clock(),
        waypoints: waypoints.length,
      },
    }
  }
}

export async function viewportCenterFromDebugger(
  debug: DebuggerManager,
  tabId: number,
): Promise<Point> {
  const result = await debug.send(tabId, 'Runtime.evaluate', {
    expression: `(() => {
      const vv = window.visualViewport;
      const w = Math.max(1, Math.round(vv?.width || window.innerWidth || document.documentElement.clientWidth || 1280));
      const h = Math.max(1, Math.round(vv?.height || window.innerHeight || document.documentElement.clientHeight || 800));
      return { x: Math.round(w / 2), y: Math.round(h / 2) };
    })()`,
    returnByValue: true,
  })
  return normalizePoint(result.result.value)
}

async function safeInitialCursorPosition(
  resolver: (tabId: number) => Promise<Point>,
  tabId: number,
): Promise<Point> {
  try {
    return normalizePoint(await resolver(tabId))
  } catch {
    return defaultInitialCursorPosition()
  }
}

async function defaultInitialCursorPosition(): Promise<Point> {
  return { x: 640, y: 400 }
}

function normalizePoint(value: unknown): Point {
  if (!value || typeof value !== 'object') return { x: 640, y: 400 }
  const p = value as Partial<Point>
  const x = typeof p.x === 'number' && Number.isFinite(p.x) ? Math.round(p.x) : 640
  const y = typeof p.y === 'number' && Number.isFinite(p.y) ? Math.round(p.y) : 400
  return { x: Math.max(0, x), y: Math.max(0, y) }
}
