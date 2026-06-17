import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DouyinUiParams } from '../types'
import { ensureVisibilityOverride } from './visibility-keepalive'

export interface DouyinUiHandlerDeps {
  /** Chrome API; injectable for tests. Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
}

interface DouyinUiResult {
  ok: boolean
  op: string
  detail: string
}

/**
 * douyin_ui — background-capable, in-page DOM driver for the Douyin steps that
 * a plain CDP click/key can't do off-screen:
 *   - op='sort': synthetic-HOVER the 筛选 trigger to reveal its (JS-driven) sort
 *     panel, then click the requested option (最多点赞 / 最新发布 / 综合排序).
 *     A plain click does NOT open this panel — it needs pointerenter/mouseenter.
 *   - op='open_comments': click the comment icon ([data-e2e="feed-comment-icon"])
 *     to open the comment panel (replaces the foreground-only 'x' shortcut).
 *   - op='pause': pause all <video> elements.
 * All via chrome.scripting.executeScript — works on a background / minimised tab.
 */
export const douyinUiHandler = (deps: DouyinUiHandlerDeps): ActionHandler<DouyinUiParams> => {
  return async (tabId, params) => {
    const api = deps.chrome ?? globalThis.chrome
    if (!api?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting unavailable', true)
    }
    // 后台保活:让页面以为自己可见(每次导航后 document 重载会失效,故每步重注入)
    await ensureVisibilityOverride(api, tabId)
    const op = params?.op ?? ''
    const label = params?.label ?? ''

    let result: DouyinUiResult | undefined
    try {
      const results = await api.scripting.executeScript({
        target: { tabId, allFrames: false },
        func: douyinUiInPage,
        args: [op, label],
      })
      result = results?.[0]?.result as DouyinUiResult | undefined
    } catch (e) {
      throw new ActionFailureError('HANDLER_ERROR', 'douyin_ui inject failed: ' + (e instanceof Error ? e.message : String(e)), true)
    }

    if (!result || !result.ok) {
      throw new ActionFailureError('HANDLER_ERROR', 'douyin_ui(' + op + '): ' + (result?.detail || 'failed'), true)
    }
    return { ok: true, elapsed_ms: 0, payload: { ...result } }
  }
}

/** Injected page func. Pure DOM. */
async function douyinUiInPage(op: string, label: string): Promise<DouyinUiResult> {
  const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms))
  const vis = (e: Element | null | undefined): e is HTMLElement => {
    if (!(e instanceof HTMLElement)) return false
    const b = e.getBoundingClientRect()
    return b.width > 0 && b.height > 0
  }
  const norm = (s: string | null | undefined) => (s || '').replace(/\s+/g, '')
  const center = (el: HTMLElement) => { const r = el.getBoundingClientRect(); return { clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 } }
  const byText = (t: string): HTMLElement | undefined =>
    Array.from(document.querySelectorAll<HTMLElement>('span,div,button,li,[role="button"],[role="menuitem"]'))
      .filter(e => norm(e.textContent) === t && vis(e))
      .sort((a, b) => { const A = a.getBoundingClientRect(), B = b.getBoundingClientRect(); return A.width * A.height - B.width * B.height })[0]
  const click = (el: HTMLElement) => {
    const c = center(el)
    el.dispatchEvent(new PointerEvent('pointerdown', Object.assign({ bubbles: true, composed: true, pointerId: 1, pointerType: 'mouse' }, c)))
    ;['mousedown', 'pointerup', 'mouseup', 'click'].forEach(t => el.dispatchEvent(new MouseEvent(t, Object.assign({ bubbles: true, cancelable: true, composed: true, view: window }, c))))
    if (typeof el.click === 'function') { try { el.click() } catch { /* detached */ } }
  }
  const hover = (el: HTMLElement) => {
    const c = center(el)
    el.dispatchEvent(new PointerEvent('pointerover', Object.assign({ bubbles: true, composed: true, pointerId: 1, pointerType: 'mouse' }, c)))
    el.dispatchEvent(new PointerEvent('pointerenter', Object.assign({ bubbles: false, composed: true, pointerId: 1, pointerType: 'mouse' }, c)))
    el.dispatchEvent(new MouseEvent('mouseover', Object.assign({ bubbles: true, composed: true, view: window }, c)))
    el.dispatchEvent(new MouseEvent('mouseenter', Object.assign({ bubbles: false, composed: true, view: window }, c)))
    el.dispatchEvent(new MouseEvent('mousemove', Object.assign({ bubbles: true, composed: true, view: window }, c)))
  }

  if (op === 'pause') {
    let n = 0
    document.querySelectorAll('video').forEach(v => { try { v.pause(); n++ } catch { /* ignore */ } })
    return { ok: true, op, detail: 'paused ' + n }
  }

  if (op === 'open_comments') {
    const hasList = () => Array.from(document.querySelectorAll('#merge-all-comment-container [data-e2e="comment-list"], [data-e2e="comment-list"]')).some(vis)
    if (hasList()) return { ok: true, op, detail: 'already_open' }
    const btn = document.querySelector<HTMLElement>('[data-e2e="feed-comment-icon"],[data-e2e="comment-icon"],[data-e2e="video-comment"],[aria-label*="评论"]')
    if (!btn) return { ok: false, op, detail: 'comment_icon_not_found' }
    click(btn)
    await sleep(1500)
    return { ok: hasList(), op, detail: hasList() ? 'opened' : 'clicked_but_no_list' }
  }

  if (op === 'sort') {
    const flt = byText('筛选')
    if (!flt) return { ok: false, op, detail: 'filter_trigger_not_found' }
    hover(flt)
    await sleep(900)
    const want = label || '最多点赞'
    const alt = want === '最多点赞' ? '点赞最多' : want === '最新发布' ? '发布时间' : ''
    const opt = byText(want) || (alt ? byText(alt) : undefined)
    if (!opt) return { ok: false, op, detail: 'sort_option_not_found:' + want }
    click(opt)
    await sleep(1500)
    return { ok: true, op, detail: 'sorted:' + want }
  }

  return { ok: false, op, detail: 'unknown_op:' + op }
}
