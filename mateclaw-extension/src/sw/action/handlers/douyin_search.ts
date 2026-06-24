import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DouyinSearchParams } from '../types'
import { ensureVisibilityOverride } from './visibility-keepalive'
import { installNetObserver, netSignalSince } from '../net_observer'

export interface DouyinSearchHandlerDeps {
  /** Chrome API; injectable for tests. Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
}

interface DouyinSearchResult {
  typed: boolean
  clicked: boolean
  value: string
  inputSelector: string
  buttonSelector: string
  reason: string
}

/** Throw CANCELLED at an await boundary if the run was aborted (action.cancel). */
function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw new ActionFailureError('CANCELLED', 'douyin_search aborted before injection', false)
  }
}

/**
 * douyin_search — drive Douyin's REAL search flow entirely in-page (DOM):
 *   1. type the keyword into the React-controlled search input
 *      ([data-e2e="searchbar-input"]) using the native value setter + the
 *      `_valueTracker` reset so React registers the change;
 *   2. click the search button ([data-e2e="searchbar-button"]) with a full
 *      pointer→mouse→native-click sequence (falls back to Enter on the input).
 *
 * All in-page (chrome.scripting.executeScript) — no CDP input, no URL-direct —
 * so it works on a background / minimised tab and mirrors a real user. This is
 * the exact flow validated in the page console before wiring it here.
 */
export const douyinSearchHandler = (deps: DouyinSearchHandlerDeps): ActionHandler<DouyinSearchParams> => {
  return async (tabId, params, deadlineMs, signal) => {
    const api = deps.chrome ?? globalThis.chrome
    if (!api?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting unavailable', true)
    }
    // 取消优先:在任何副作用(保活注入/搜索注入)之前先看 signal,已取消则直接抛 CANCELLED 不注入。
    throwIfAborted(signal)
    // 后台保活:让页面以为自己可见,避免后台暂停加载/续拉(必须赶在用户切后台前就位)
    await ensureVisibilityOverride(api, tabId)
    const keyword = (params?.keyword ?? '').trim()
    if (!keyword) {
      throw new ActionFailureError('HANDLER_ERROR', 'keyword is required', false)
    }
    // 接口确认:点搜索【之前】先装观察器登记 general/search 结果接口(精确到 general/search,排除
    // search/sug 联想词)。点击后据该接口回包直接确认搜索结果就绪 —— 替代后端"等 1.2s + 8 轮看
    // DOM 树含关键词/筛选UI"(约 13s)。搜索是页内 SPA(不整页导航),点击前注入即可持续捕获。
    // best-effort,装不上则回执无 searchConfirmed、后端原样退回 DOM 校验轮询(零回归)。
    await installNetObserver(api, tabId, [
      { key: 'search', urlSource: 'aweme/v1/web/general/search/', captureBody: false },
    ])
    const searchSinceTs = Date.now()
    // ensureVisibilityOverride 也是一次注入/await,可能跨过一个取消窗口——执行真正搜索前再查一次。
    throwIfAborted(signal)

    let result: DouyinSearchResult | undefined
    try {
      const results = await api.scripting.executeScript({
        target: { tabId, allFrames: false },
        func: douyinSearchInPage,
        args: [keyword],
      })
      result = results?.[0]?.result as DouyinSearchResult | undefined
    } catch (e) {
      throw new ActionFailureError('HANDLER_ERROR', 'douyin_search inject failed: ' + (e instanceof Error ? e.message : String(e)), true)
    }

    if (!result || !result.typed) {
      throw new ActionFailureError('HANDLER_ERROR', 'douyin_search: ' + (result?.reason || 'search_input_not_found'), true)
    }
    // 已输入并点了搜索:在剩余预算内轮询 general/search 回包确认结果就绪;命中→回执 searchConfirmed=true,
    // 后端据此跳过 DOM 校验轮询。未命中(慢网/SPA 整页重载致观察器失效)→ false,后端原样轮询(零回归)。
    const searchConfirmed = result.clicked
      ? await waitSearchNetConfirm(api, tabId, searchSinceTs, signal, deadlineMs)
      : false
    return { ok: true, elapsed_ms: 0, payload: { ...result, searchConfirmed } }
  }
}

/**
 * 在有限预算内轮询"搜索结果接口(general/search)是否回包"。命中即返回 true(结果就绪)。
 * 结果通常 1-3s 回包,预算封顶 8s(不超过下发 deadline 余量);超时返回 false,由后端原样退回
 * "等待 + DOM 校验"轮询(零回归)。读 MAIN world 观察器写在 documentElement 的 data-mc-net-search。
 */
async function waitSearchNetConfirm(
  chromeApi: typeof globalThis.chrome,
  tabId: number,
  sinceTs: number,
  signal: AbortSignal | undefined,
  deadlineMs: number,
): Promise<boolean> {
  const budget = deadlineMs && deadlineMs > 0 ? Math.min(8000, Math.max(1500, deadlineMs - 800)) : 8000
  const until = Date.now() + budget
  for (;;) {
    if (signal?.aborted) return false
    if (await netSignalSince(chromeApi, tabId, 'search', sinceTs, { windowMs: 20_000 })) return true
    if (Date.now() >= until) return false
    await new Promise(resolve => setTimeout(resolve, 200))
  }
}

/** Injected page func. Pure DOM; React-aware input set + synthetic click. */
function douyinSearchInPage(keyword: string): DouyinSearchResult {
  const vis = (e: Element | null | undefined): e is HTMLElement => {
    if (!(e instanceof HTMLElement)) return false
    const b = e.getBoundingClientRect()
    return b.width > 0 && b.height > 0
  }

  // 1) locate the search input
  const inputSelectors = ['input[data-e2e="searchbar-input"]', 'input[placeholder*="搜索"]', 'input[type="search"]', 'header input[type="text"]']
  let input: HTMLInputElement | HTMLTextAreaElement | null = null
  let inputSelector = ''
  for (const s of inputSelectors) {
    const el = Array.from(document.querySelectorAll(s)).find(vis)
    if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) { input = el; inputSelector = s; break }
  }
  if (!input) {
    return { typed: false, clicked: false, value: '', inputSelector: '', buttonSelector: '', reason: 'search_input_not_found' }
  }

  // 2) React-controlled set value: native setter + _valueTracker reset + input/change
  const proto = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
  const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set
  const last = input.value
  try { input.focus() } catch { /* ignore */ }
  if (setter) setter.call(input, keyword); else input.value = keyword
  const tracker = (input as unknown as { _valueTracker?: { setValue(v: string): void } })._valueTracker
  if (tracker) tracker.setValue(last)
  input.dispatchEvent(new Event('input', { bubbles: true }))
  input.dispatchEvent(new Event('change', { bubbles: true }))
  const typed = input.value === keyword

  // 3) locate the search button
  const btnSelectors = ['[data-e2e="searchbar-button"]', 'button[type="submit"]']
  let btn: HTMLElement | null = null
  let buttonSelector = ''
  for (const s of btnSelectors) {
    const el = Array.from(document.querySelectorAll(s)).find(vis)
    if (el) { btn = el; buttonSelector = s; break }
  }
  if (!btn) {
    const cand = (Array.from(document.querySelectorAll('button,[role="button"],span,div')) as HTMLElement[])
      .filter(e => (e.textContent || '').replace(/\s+/g, '') === '搜索' && vis(e))
      .sort((a, b) => { const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect(); return ra.width * ra.height - rb.width * rb.height })
    if (cand[0]) { btn = cand[0]; buttonSelector = 'text=搜索' }
  }

  // 4) click search button (synthetic pointer/mouse + native), else Enter on input
  let clicked = false
  if (btn) {
    const r = btn.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2
    const base = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, button: 0 }
    const ptr = Object.assign({}, base, { pointerId: 1, pointerType: 'mouse', isPrimary: true })
    btn.dispatchEvent(new PointerEvent('pointerover', ptr))
    btn.dispatchEvent(new PointerEvent('pointerenter', Object.assign({}, ptr, { bubbles: false })))
    btn.dispatchEvent(new PointerEvent('pointerdown', ptr))
    btn.dispatchEvent(new MouseEvent('mousedown', base))
    btn.dispatchEvent(new PointerEvent('pointerup', ptr))
    btn.dispatchEvent(new MouseEvent('mouseup', base))
    btn.dispatchEvent(new MouseEvent('click', base))
    if (typeof btn.click === 'function') { try { btn.click() } catch { /* detached */ } }
    clicked = true
  } else {
    const ev = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true } as KeyboardEventInit
    input.dispatchEvent(new KeyboardEvent('keydown', ev))
    input.dispatchEvent(new KeyboardEvent('keyup', ev))
    buttonSelector = 'Enter'
  }

  return { typed, clicked, value: input.value, inputSelector, buttonSelector, reason: typed ? '' : 'value_not_set' }
}
