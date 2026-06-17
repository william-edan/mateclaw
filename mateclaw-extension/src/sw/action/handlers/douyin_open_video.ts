import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DouyinOpenVideoParams } from '../types'
import { ensureVisibilityOverride } from './visibility-keepalive'

export interface DouyinOpenVideoHandlerDeps {
  /** Chrome API; injectable for tests. Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
}

interface DouyinOpenVideoResult {
  clicked: boolean
  index: number
  total: number
  title: string
  reason: string
}

/**
 * douyin_open_video — open the index-th VIDEO result by clicking its cover card
 * element in-page (DOM). Douyin search result cards have NO /video/ anchors and
 * no data-e2e; the cover is an <img> with cursor:pointer that opens the video on
 * click. We pick VIDEO cards only (cover imgs in the results region whose card
 * text carries a MM:SS duration — image-text "图文" posts have no duration and
 * are skipped), order them top-left, and click the index-th cover element
 * directly. Clicking the real element (not a guessed coordinate) means it can
 * never land on a neighbouring card ("sometimes opens the second video" bug).
 * Background-capable (chrome.scripting.executeScript, no CDP input).
 */
export const douyinOpenVideoHandler = (deps: DouyinOpenVideoHandlerDeps): ActionHandler<DouyinOpenVideoParams> => {
  return async (tabId, params) => {
    const api = deps.chrome ?? globalThis.chrome
    if (!api?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting unavailable', true)
    }
    // 后台保活:先让页面以为可见,使视频卡时长能正常渲染,避免第一个视频被当图文跳过而点到第二个
    await ensureVisibilityOverride(api, tabId)
    const index = Math.max(0, Math.floor(params?.index ?? 0))

    let result: DouyinOpenVideoResult | undefined
    try {
      const results = await api.scripting.executeScript({
        target: { tabId, allFrames: false },
        func: douyinOpenVideoInPage,
        args: [index],
      })
      result = results?.[0]?.result as DouyinOpenVideoResult | undefined
    } catch (e) {
      throw new ActionFailureError('HANDLER_ERROR', 'douyin_open_video inject failed: ' + (e instanceof Error ? e.message : String(e)), true)
    }

    if (!result || !result.clicked) {
      throw new ActionFailureError('HANDLER_ERROR', 'douyin_open_video: ' + (result?.reason || 'video_card_not_found'), true)
    }
    return { ok: true, elapsed_ms: 0, payload: { ...result } }
  }
}

/** Injected page func. Wait for the result list to settle, then click the index-th VIDEO. */
async function douyinOpenVideoInPage(index: number): Promise<DouyinOpenVideoResult> {
  const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms))
  const vis = (e: Element | null | undefined): e is HTMLElement => {
    if (!(e instanceof HTMLElement)) return false
    const b = e.getBoundingClientRect()
    return b.width > 0 && b.height > 0
  }
  // Cover imgs in the results region (below the header/tab bar). Videos carry a
  // MM:SS duration in the card text; image-text "图文" posts do not.
  const collectCards = () =>
    Array.from(document.querySelectorAll<HTMLElement>('img, picture'))
      .map(im => ({ im, b: im.getBoundingClientRect() }))
      .filter(c => c.b.width >= 100 && c.b.height >= 100 && c.b.top > 150 && vis(c.im))
      .map(c => {
        let card: HTMLElement = c.im
        for (let k = 0; k < 6 && card.parentElement; k++) card = card.parentElement
        const text = (card.innerText || '')
        const isVideo = /\b\d{1,2}:\d{2}\b/.test(text) && !/^\s*图文/.test(text)
        return { ...c, card, isVideo, title: text.replace(/\s+/g, ' ').trim().slice(0, 40) }
      })
      .filter(c => c.isVideo)
      .sort((p, q) => (Math.round(p.b.top / 100) - Math.round(q.b.top / 100)) || (p.b.left - q.b.left))

  // 等结果区视频卡渲染稳定再选:连续两次数量相同且 >= index+1 视为稳定,最多 ~3s。
  // 后台/刚导航时第一个视频的时长可能还没渲染(会被当图文漏掉),不等就会点到第二个。
  let cards = collectCards()
  let stable = 0
  for (let t = 0; t < 8; t++) {
    const prev = cards.length
    await sleep(300)
    cards = collectCards()
    if (prev > 0 && cards.length === prev && cards.length >= index + 1) {
      if (++stable >= 2) break
    } else {
      stable = 0
    }
  }

  if (!cards.length) {
    return { clicked: false, index, total: 0, title: '', reason: 'no_video_cover_cards' }
  }
  const i = Math.min(index, cards.length - 1)
  const chosen = cards[i]

  // The cover img itself is cursor:pointer; clicking it opens the video.
  // Prefer a cursor:pointer ancestor if the img isn't the clickable surface.
  let el: HTMLElement = chosen.im
  let cur: HTMLElement | null = chosen.im
  for (let d = 0; cur && d < 8; d++, cur = cur.parentElement) {
    const cs = getComputedStyle(cur)
    if (cs.cursor === 'pointer' || cur.getAttribute('role') === 'button' || cur.tagName === 'A') { el = cur; break }
  }

  try { el.scrollIntoView({ block: 'center', inline: 'center' }) } catch { /* ignore */ }
  const r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2
  const base = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, button: 0 }
  const ptr = Object.assign({}, base, { pointerId: 1, pointerType: 'mouse', isPrimary: true })
  el.dispatchEvent(new PointerEvent('pointerover', ptr))
  el.dispatchEvent(new PointerEvent('pointerenter', Object.assign({}, ptr, { bubbles: false })))
  el.dispatchEvent(new PointerEvent('pointerdown', ptr))
  el.dispatchEvent(new MouseEvent('mousedown', base))
  el.dispatchEvent(new PointerEvent('pointerup', ptr))
  el.dispatchEvent(new MouseEvent('mouseup', base))
  el.dispatchEvent(new MouseEvent('click', base))
  if (typeof el.click === 'function') { try { el.click() } catch { /* detached */ } }

  return { clicked: true, index: i, total: cards.length, title: chosen.title, reason: '' }
}
