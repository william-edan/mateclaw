import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ScrollParams } from '../types'
import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { DOM_ONLY_NO_CDP_FALLBACK } from './debug-flags'

export interface ScrollHandlerDeps {
  debugger: DebuggerManager
  /** Chrome API; injectable for tests. Defaults to global chrome. Used by the
   *  background-capable DOM-scroll fast-path (chrome.scripting.executeScript). */
  chrome?: typeof globalThis.chrome
  clock?: () => number
  random?: () => number
  /** Per-segment delay (default: log-normal ~60-180ms - wheel push cadence) */
  segmentIntervalMs?: () => number
  /** Returns the viewport center for the current tab (defaults to a static
   *  midpoint; real impl can use chrome.tabs.get + chrome.action.getZoom, but
   *  the executor doesn't have to be pixel-perfect - the page coords don't
   *  matter for wheel events, only deltas). */
  viewportCenter?: (tabId: number) => Promise<{ x: number; y: number }>
}

/**
 * scroll handler.
 *
 * Params: { direction: 'up'|'down'|'left'|'right', distance_px: number, segments?: number }
 *
 * Flow:
 *   1. debugger.attach(tabId).
 *   2. center = viewportCenter(tabId) (default to {640, 400} if not given).
 *   3. segments = params.segments ?? 5.
 *   4. delta-per-segment = distance_px / segments, signed by direction:
 *        up    -> deltaY = -d
 *        down  -> deltaY = +d
 *        left  -> deltaX = -d
 *        right -> deltaX = +d
 *   5. For i in 0..segments-1:
 *      a. send Input.dispatchMouseEvent { type:'mouseWheel', x, y, deltaX, deltaY }
 *      b. if i < segments-1, wait segmentIntervalMs()
 *   6. return Success.
 *
 * Throws ActionFailureError('SESSION_DETACHED') on detach.
 */
export const scrollHandler = (deps: ScrollHandlerDeps): ActionHandler<ScrollParams> => {
  const clock = deps.clock ?? Date.now
  const random = deps.random ?? Math.random
  const segmentIntervalMs = deps.segmentIntervalMs ?? (() => defaultSegmentIntervalMs(random))
  const viewportCenter = deps.viewportCenter ?? (async () => ({ x: 640, y: 400 }))

  return async (tabId, params, _deadlineMs) => {
    const startedAt = clock()

    const { x, y } = hasPoint(params) ? { x: params.x, y: params.y } : await viewportCenter(tabId)

    // 后台静默优先:先用页内 DOM 滚动(executeScript 定位 (x,y) 命中元素或抖音评论
    // 列表的可滚动容器,scrollBy/scrollTop 并派发 scroll/wheel 事件触发懒加载续拉)。
    // 这条路不经 CDP Input、不依赖窗口活动tab/焦点,最小化或切到其他 tab 仍可滚动
    // (抖音获客后台采集的关键)。只有页内未找到可滚动容器/脚本不可用时才回退 CDP 滚轮。
    const dom = await tryDomScrollAtPoint(deps.chrome, tabId, params.direction, params.distance_px, x, y)
    if (dom.ok) {
      return {
        ok: true,
        elapsed_ms: Math.max(0, clock() - startedAt),
        payload: { mode: 'dom_scroll', ...dom.payload },
      }
    }

    // 【临时调试 · DOM_ONLY】页内 DOM 未找到可滚动容器即直接报失败,不回退 CDP 滚轮,
    // 让"采集评论"这步的 DOM 失败暴露出来。见 debug-flags.ts。
    if (DOM_ONLY_NO_CDP_FALLBACK) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        `dom_only_no_cdp: DOM 滚动未找到可滚动容器 (dir=${params.direction}); domReason=${dom.reason}`,
        false,
      )
    }

    try {
      await deps.debugger.attach(tabId)

      const segmentCount = normalizeSegments(params.segments)
      const distances = integerSegments(params.distance_px, segmentCount)

      for (let i = 0; i < distances.length; i += 1) {
        const distance = signedDistance(params.direction, distances[i] ?? 0)
        const deltaX = params.direction === 'left' || params.direction === 'right' ? distance : 0
        const deltaY = params.direction === 'up' || params.direction === 'down' ? distance : 0

        await deps.debugger.send(tabId, 'Input.dispatchMouseEvent', {
          type: 'mouseWheel',
          x,
          y,
          deltaX,
          deltaY,
          modifiers: 0,
        })

        if (i < distances.length - 1) {
          await sleep(segmentIntervalMs())
        }
      }

      return {
        ok: true,
        elapsed_ms: Math.max(0, clock() - startedAt),
        payload: { mode: 'cdp_wheel', domReason: dom.reason },
      }
    } catch (err) {
      if (err instanceof SessionDetachedError) {
        throw new ActionFailureError('SESSION_DETACHED', err.message, true)
      }
      throw err
    }
  }
}

/**
 * Background-capable DOM scroll. Injects a page func (chrome.scripting.executeScript)
 * that locates the scrollable container at (x,y) — or, for Douyin, the comment
 * list — and scrolls it via scrollBy/scrollTop plus a dispatched WheelEvent so a
 * virtualised list lazy-loads its next page. Runs in the page regardless of tab
 * focus/visibility, unlike CDP Input.dispatchMouseEvent. Returns ok only when a
 * scrollable container was found (else the caller falls back to the CDP wheel).
 */
async function tryDomScrollAtPoint(
  chromeApi: typeof globalThis.chrome | undefined,
  tabId: number,
  direction: ScrollParams['direction'],
  distancePx: number,
  x: number,
  y: number,
): Promise<{ ok: true; payload: Record<string, unknown> } | { ok: false; reason: string; payload?: Record<string, unknown> }> {
  const api = chromeApi ?? globalThis.chrome
  if (!api?.scripting?.executeScript) return { ok: false, reason: 'scripting_unavailable' }
  try {
    const results = await api.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: domScrollAtPointInPage,
      args: [direction, distancePx, x, y],
    })
    const payload = results?.[0]?.result as ({ found?: boolean } & Record<string, unknown>) | undefined
    if (payload?.found) return { ok: true, payload }
    return { ok: false, reason: 'dom_scroll_container_not_found', payload }
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : 'dom_scroll_exception' }
  }
}

/** Injected into the page by {@link tryDomScrollAtPoint}. Pure DOM; no CDP. */
function domScrollAtPointInPage(
  direction: ScrollParams['direction'],
  distancePx: number,
  x: number,
  y: number,
): Record<string, unknown> {
  const delta = Math.max(1, Math.round(Math.abs(distancePx)))
  const dx = direction === 'left' ? -delta : direction === 'right' ? delta : 0
  const dy = direction === 'up' ? -delta : direction === 'down' ? delta : 0

  const isScrollable = (el: Element | null): el is HTMLElement => {
    if (!(el instanceof HTMLElement)) return false
    if (dy !== 0 && el.scrollHeight > el.clientHeight + 4) return true
    if (dx !== 0 && el.scrollWidth > el.clientWidth + 4) return true
    return false
  }
  const ancestorScrollable = (start: Element | null): HTMLElement | null => {
    let cur: Element | null = start
    let depth = 0
    while (cur instanceof HTMLElement && depth++ < 12) {
      if (cur !== document.body && cur !== document.documentElement && isScrollable(cur)) return cur
      cur = cur.parentElement
    }
    return null
  }

  const candidates: HTMLElement[] = []
  const seen = new Set<HTMLElement>()
  const add = (el: HTMLElement | null) => {
    if (el && !seen.has(el)) { seen.add(el); candidates.push(el) }
  }

  // 1) 抖音评论列表优先(后台采集主用途)
  for (const sel of ['#merge-all-comment-container [data-e2e="comment-list"]', '[data-e2e="comment-list"]']) {
    for (const el of Array.from(document.querySelectorAll<HTMLElement>(sel))) {
      add(isScrollable(el) ? el : ancestorScrollable(el))
    }
  }
  // 2) (x,y) 命中元素的最近可滚动祖先
  if (typeof document.elementsFromPoint === 'function') {
    for (const hit of document.elementsFromPoint(x, y).slice(0, 6)) {
      add(ancestorScrollable(hit))
    }
  } else {
    add(ancestorScrollable(document.elementFromPoint(x, y)))
  }
  // 3) 文档级兜底
  add((document.scrollingElement as HTMLElement | null) ?? document.documentElement)

  const dispatchWheel = (el: HTMLElement) => {
    el.dispatchEvent(new WheelEvent('wheel', {
      bubbles: true, cancelable: true, composed: true,
      clientX: x, clientY: y, deltaX: dx, deltaY: dy, deltaMode: 0,
    }))
  }

  let moved = false
  let container: HTMLElement | null = null
  for (const el of candidates) {
    const beforeTop = el.scrollTop
    const beforeLeft = el.scrollLeft
    el.scrollBy({ top: dy, left: dx, behavior: 'auto' })
    if (Math.abs(el.scrollTop - beforeTop) < 1 && Math.abs(el.scrollLeft - beforeLeft) < 1) {
      el.scrollTop = beforeTop + dy
      el.scrollLeft = beforeLeft + dx
    }
    dispatchWheel(el)
    if (!container) container = el
    if (Math.abs(el.scrollTop - beforeTop) > 0 || Math.abs(el.scrollLeft - beforeLeft) > 0) {
      moved = true
      container = el
      break
    }
  }

  // 即使 moved=false(可能已到底/容器不滚)也已派发 wheel/scroll 事件,
  // 抖音评论虚拟列表据此续拉;found=true 即视为本轮 DOM 滚动成功。
  return {
    found: candidates.length > 0,
    moved,
    tag: container ? container.tagName.toLowerCase() : null,
    e2e: container ? (container.getAttribute('data-e2e') || undefined) : undefined,
    scrollTop: container ? container.scrollTop : null,
    scrollHeight: container ? container.scrollHeight : null,
    clientHeight: container ? container.clientHeight : null,
  }
}

function hasPoint(params: ScrollParams): params is ScrollParams & { x: number; y: number } {
  return typeof params.x === 'number' && Number.isFinite(params.x)
    && typeof params.y === 'number' && Number.isFinite(params.y)
}

function normalizeSegments(segments: number | undefined): number {
  return Math.max(1, Math.floor(segments ?? 5))
}

function integerSegments(distancePx: number, segments: number): number[] {
  const total = Math.max(0, Math.round(Math.abs(distancePx)))
  const base = Math.floor(total / segments)
  const remainder = total % segments

  return Array.from({ length: segments }, (_, i) => base + (i < remainder ? 1 : 0))
}

function signedDistance(direction: ScrollParams['direction'], distance: number): number {
  if (direction === 'up' || direction === 'left') return -distance
  return distance
}

function defaultSegmentIntervalMs(random: () => number): number {
  const u1 = clampUnit(random())
  const u2 = clampUnit(random())
  const normal = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
  const median = Math.log(105)
  const sigma = 0.28
  return Math.round(clamp(Math.exp(median + sigma * normal), 60, 180))
}

function clampUnit(value: number): number {
  return clamp(value, Number.EPSILON, 1 - Number.EPSILON)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

async function sleep(ms: number): Promise<void> {
  await new Promise<void>(resolve => setTimeout(resolve, Math.max(0, ms)))
}
