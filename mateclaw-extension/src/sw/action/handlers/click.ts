import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ClickParams } from '../types'
import { DOM_ONLY_NO_CDP_FALLBACK } from './debug-flags'

export interface ClickHandlerDeps {
  debugger: DebuggerManager
  /** Chrome API; injectable for tests. Defaults to global chrome. Used by the
   *  background-capable DOM-click fast-path (chrome.scripting.executeScript). */
  chrome?: typeof globalThis.chrome
  clock?: () => number
  random?: () => number
  /** Extra micro-delay before mouseReleased (default: 30-100ms log-normal) */
  pressHoldMs?: () => number
  /**
   * Awaited AFTER the click lands so the click's DOM effect — a dropdown /
   * filter panel / menu opening — has time to render before the agent's next
   * observe reads the tree. Without this the click handler returns the instant
   * the mouse event is dispatched (~50ms), the agent observes immediately, and
   * observe's quiet-settle fires before the (async, React) panel paints — so the
   * panel is missed, the agent thinks the click failed and re-clicks, which
   * TOGGLES the panel shut (the "点开了又没了 / 好几次没获取到" flicker).
   * Default: an adaptive MutationObserver settle in the page via
   * chrome.scripting — waits for the post-click DOM burst to go quiet (bounded),
   * and returns fast when the click changed nothing. No-op (resolves at once)
   * when chrome.scripting is unavailable, e.g. unit tests.
   */
  settleAfterClick?: (tabId: number) => Promise<void>
}

type MouseButton = NonNullable<ClickParams['button']>

/**
 * click handler.
 *
 * Flow:
 *   1. debugger.attach(tabId) - idempotent.
 *   2. For each click in 1..click_count:
 *      a. send Input.dispatchMouseEvent { type: 'mousePressed', x, y, button, clickCount }
 *      b. wait pressHoldMs() (default: log-normal ~50ms - human finger delay)
 *      c. send Input.dispatchMouseEvent { type: 'mouseReleased', x, y, button, clickCount }
 *      d. if not last click, wait ~30-150ms between clicks (double/triple-click intervals)
 *   3. return Success.
 *
 * Throws ActionFailureError('SESSION_DETACHED') if DebuggerManager throws
 * SessionDetachedError (e.g. DevTools opened mid-click).
 */
export const clickHandler = (deps: ClickHandlerDeps): ActionHandler<ClickParams> => {
  const clock = deps.clock ?? Date.now
  const random = deps.random ?? Math.random
  const pressHoldMs = deps.pressHoldMs ?? (() => logNormalMs(55, 0.35, 30, 100, random))
  const settleAfterClick = deps.settleAfterClick ?? defaultSettleAfterClick

  return async (tabId, params, _deadlineMs, signal) => {
    const startedAt = clock()

    const button = params.button ?? 'left'
    const clickCount = normalizeClickCount(params.click_count)

    // 取消优先:点击是副作用,DOM 合成点击/CDP 注入前先看 signal,已取消则抛 CANCELLED 不点击。
    if (signal?.aborted) {
      throw new ActionFailureError('CANCELLED', 'click aborted before injection', false)
    }

    // 后台静默优先:页内 DOM 合成点击(elementFromPoint(x,y) → pointer/mouse 事件
    // + native click)。不经 CDP Input、不依赖窗口活动tab/焦点,最小化或切到其他
    // tab 也能落地真实点击(抖音获客排序筛选/排序选项/点视频的关键)。命中元素即视
    // 为成功;未命中或目标是 input[type=file] 等需要"可信点击"的控件时回退 CDP。
    const dom = await tryDomClickAtPoint(deps.chrome, tabId, params.x, params.y, button)
    if (dom.ok) {
      try {
        await settleAfterClick(tabId)
      } catch {
        // ignore — the click succeeded; settling is only a timing aid
      }
      return {
        ok: true,
        elapsed_ms: Math.max(0, clock() - startedAt),
        payload: { mode: 'dom_click', ...dom.payload },
      }
    }

    // 【临时调试 · DOM_ONLY】页内 DOM 点击未命中即直接报失败,不回退 CDP,
    // 让"排序 / 点视频"这步的 DOM 失败暴露出来(而非被 CDP 悄悄兜底)。见 debug-flags.ts。
    if (DOM_ONLY_NO_CDP_FALLBACK) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        `dom_only_no_cdp: DOM 点击未命中可点击元素 (x=${params.x}, y=${params.y}); domReason=${dom.reason}`,
        false,
      )
    }

    try {
      await deps.debugger.attach(tabId)

      for (let clickIndex = 1; clickIndex <= clickCount; clickIndex++) {
        await dispatchClickEvent(deps.debugger, tabId, 'mousePressed', params.x, params.y, button, clickIndex)
        await sleep(pressHoldMs())
        await dispatchClickEvent(deps.debugger, tabId, 'mouseReleased', params.x, params.y, button, clickIndex)

        if (clickIndex < clickCount) {
          await sleep(logNormalMs(80, 0.45, 30, 150, random))
        }
      }

      // Let the click's DOM effect (a dropdown / filter panel / menu opening)
      // render before we return, so the agent's next observe captures it
      // instead of racing the async paint. Bounded + best-effort: a settle that
      // fails must NEVER fail a click that already landed.
      try {
        await settleAfterClick(tabId)
      } catch {
        // ignore — the click succeeded; settling is only a timing aid
      }

      return {
        ok: true,
        elapsed_ms: Math.max(0, clock() - startedAt),
        payload: { mode: 'cdp_click', domReason: dom.reason },
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
 * Background-capable DOM click. Injects a page func (chrome.scripting.executeScript)
 * that resolves the element at (x,y) and fires a full pointer→mouse→native-click
 * sequence on it. Runs in the page regardless of tab focus/visibility, unlike CDP
 * Input.dispatchMouseEvent (which silently no-ops in a background tab). Returns ok
 * only when a clickable element was hit; declines (so the caller falls back to CDP)
 * when nothing is at the point or the target needs a trusted click (file inputs).
 */
async function tryDomClickAtPoint(
  chromeApi: typeof globalThis.chrome | undefined,
  tabId: number,
  x: number,
  y: number,
  button: MouseButton,
): Promise<{ ok: true; payload: Record<string, unknown> } | { ok: false; reason: string; payload?: Record<string, unknown> }> {
  const api = chromeApi ?? globalThis.chrome
  if (!api?.scripting?.executeScript) return { ok: false, reason: 'scripting_unavailable' }
  try {
    const results = await api.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: domClickAtPointInPage,
      args: [x, y, button],
    })
    const payload = results?.[0]?.result as ({ clicked?: boolean; reason?: string } & Record<string, unknown>) | undefined
    if (payload?.clicked) return { ok: true, payload }
    return { ok: false, reason: payload?.reason || 'dom_click_no_target', payload }
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : 'dom_click_exception' }
  }
}

/** Injected into the page by {@link tryDomClickAtPoint}. Pure DOM; no CDP. */
function domClickAtPointInPage(x: number, y: number, button: string): Record<string, unknown> {
  const el = document.elementFromPoint(x, y)
  if (!(el instanceof Element)) return { clicked: false, reason: 'no_element_at_point' }

  // A synthetic (isTrusted=false) click cannot open the native file dialog, grant
  // user activation, etc. For controls that genuinely need a trusted click, decline
  // so the caller falls back to the CDP path.
  if (el.closest('input[type="file"], label[for]')) {
    const forFile = el.closest('label[for]') as HTMLLabelElement | null
    const ctrl = forFile?.htmlFor ? document.getElementById(forFile.htmlFor) : null
    if (el.closest('input[type="file"]') || (ctrl instanceof HTMLInputElement && ctrl.type === 'file')) {
      return { clicked: false, reason: 'needs_trusted_click_file_input' }
    }
  }

  const target = el as HTMLElement
  const btn = button === 'right' ? 2 : button === 'middle' ? 1 : 0
  const base = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, button: btn } as const
  const ptr = { ...base, pointerId: 1, pointerType: 'mouse', isPrimary: true }

  try {
    target.dispatchEvent(new PointerEvent('pointerover', ptr))
    target.dispatchEvent(new PointerEvent('pointerenter', { ...ptr, bubbles: false }))
    target.dispatchEvent(new PointerEvent('pointerdown', ptr))
    target.dispatchEvent(new MouseEvent('mousedown', base))
    target.dispatchEvent(new PointerEvent('pointerup', ptr))
    target.dispatchEvent(new MouseEvent('mouseup', base))
    target.dispatchEvent(new MouseEvent('click', base))
    // Native .click() too: covers handlers bound via the element's click() path
    // / elements that ignore synthetic MouseEvents. Harmless double-fire is rare
    // and Douyin's React handlers de-dupe on the same tick.
    if (typeof (target as HTMLElement).click === 'function') {
      try { (target as HTMLElement).click() } catch { /* element detached mid-click */ }
    }
  } catch (e) {
    return { clicked: false, reason: 'dispatch_failed:' + (e instanceof Error ? e.message : String(e)) }
  }

  return {
    clicked: true,
    tag: target.tagName.toLowerCase(),
    e2e: target.getAttribute?.('data-e2e') || undefined,
    text: (target.innerText || target.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 48),
  }
}

/**
 * Default post-click settle: run an adaptive DOM-quiet wait IN THE PAGE via
 * chrome.scripting, so a panel/menu the click opened has rendered before the
 * agent observes. Resolves immediately when chrome.scripting is unavailable
 * (unit tests / non-SW contexts). Best-effort — swallows all errors.
 *
 * In-page timing:
 *  - GRACE (≤500ms): wait for the FIRST post-click mutation. If none arrives,
 *    the click changed nothing visible → resolve at GRACE (don't stall).
 *  - QUIET (180ms): once mutations start, resolve 180ms after they stop (the
 *    panel finished painting).
 *  - CAP (1200ms): hard ceiling regardless, so a perpetually-animating page
 *    can't hang the action.
 */
async function defaultSettleAfterClick(tabId: number): Promise<void> {
  const chromeApi = (globalThis as unknown as { chrome?: typeof chrome }).chrome
  if (!chromeApi?.scripting?.executeScript) return
  try {
    await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: () =>
        new Promise<void>(resolve => {
          const root = document.documentElement
          if (!root || typeof MutationObserver === 'undefined') {
            setTimeout(resolve, 250)
            return
          }
          const GRACE = 500
          const QUIET = 180
          const CAP = 1200
          let sawMutation = false
          let done = false
          let quietTimer: ReturnType<typeof setTimeout> | undefined
          const finish = () => {
            if (done) return
            done = true
            if (quietTimer !== undefined) clearTimeout(quietTimer)
            clearTimeout(capTimer)
            clearTimeout(graceTimer)
            obs.disconnect()
            resolve()
          }
          const obs = new MutationObserver(() => {
            sawMutation = true
            if (quietTimer !== undefined) clearTimeout(quietTimer)
            quietTimer = setTimeout(finish, QUIET)
          })
          obs.observe(root, { subtree: true, childList: true, attributes: true })
          const capTimer = setTimeout(finish, CAP)
          // No DOM change within GRACE ⇒ the click had no visible effect; stop.
          const graceTimer = setTimeout(() => {
            if (!sawMutation) finish()
          }, GRACE)
        }),
    })
  } catch {
    // best-effort — a failed settle must never fail the click
  }
}

async function dispatchClickEvent(
  debug: DebuggerManager,
  tabId: number,
  type: 'mousePressed' | 'mouseReleased',
  x: number,
  y: number,
  button: MouseButton,
  clickCount: number,
): Promise<void> {
  await debug.send(tabId, 'Input.dispatchMouseEvent', {
    type,
    x,
    y,
    button,
    clickCount,
    modifiers: 0,
  })
}

function normalizeClickCount(value: number | undefined): number {
  if (value == null || !Number.isFinite(value)) return 1
  return Math.max(1, Math.floor(value))
}

function logNormalMs(
  medianMs: number,
  sigma: number,
  minMs: number,
  maxMs: number,
  random: () => number,
): number {
  const u1 = Math.max(random(), 1e-12)
  const u2 = random()
  const z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
  const sample = Math.exp(Math.log(medianMs) + sigma * z)
  return clamp(Math.round(sample), minMs, maxMs)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function sleep(ms: number): Promise<void> {
  if (!Number.isFinite(ms) || ms <= 0) return Promise.resolve()
  return new Promise(resolve => setTimeout(resolve, ms))
}
