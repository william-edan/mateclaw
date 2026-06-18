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

/** Throw CANCELLED at an await boundary if the run was aborted (action.cancel). */
function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw new ActionFailureError('CANCELLED', 'douyin_open_video aborted before injection', false)
  }
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
  return async (tabId, params, _deadlineMs, signal) => {
    const api = deps.chrome ?? globalThis.chrome
    if (!api?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting unavailable', true)
    }
    // 取消优先:任何副作用(保活/点击注入)前先看 signal,已取消则抛 CANCELLED 不注入。
    throwIfAborted(signal)
    // 后台保活:先让页面以为可见,使视频卡时长能正常渲染,避免第一个视频被当图文跳过而点到第二个
    await ensureVisibilityOverride(api, tabId)
    const index = Math.max(0, Math.floor(params?.index ?? 0))
    // 保活也是一次 await,可能跨过取消窗口——真正点击注入前再查一次。
    throwIfAborted(signal)

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
  // 结果区(top 在 header 之下)的封面占位:img/picture 只要"存在"即可,【不再要求
  // 已加载出尺寸(width/height>=100)】——3G 懒加载下封面图迟迟没出尺寸,旧逻辑会把
  // 真实视频卡全部漏掉而误判 no_video_cover_cards。视频判定改回到卡片文本:含 MM:SS
  // 时长 且非『图文』开头。点击目标仍取该卡 cursor:pointer 的可点祖先。
  const headerBottom = (() => {
    const h = document.querySelector('header') as HTMLElement | null
    const hb = h ? h.getBoundingClientRect().bottom : 0
    return Math.max(80, hb) // 没有 header 时退回 80px 经验阈值,避开顶部搜索条
  })()
  const collectCards = () =>
    Array.from(document.querySelectorAll<HTMLElement>('img, picture'))
      .map(im => ({ im, b: im.getBoundingClientRect() }))
      // 结果区:占位元素 top 在 header 之下即可;不卡 width/height,允许懒加载未出尺寸的占位
      .filter(c => c.b.top > headerBottom)
      .map(c => {
        let card: HTMLElement = c.im
        for (let k = 0; k < 6 && card.parentElement; k++) card = card.parentElement
        const text = (card.innerText || '')
        const isVideo = /\b\d{1,2}:\d{2}\b/.test(text) && !/^\s*图文/.test(text)
        return { ...c, card, isVideo, title: text.replace(/\s+/g, ' ').trim().slice(0, 40) }
      })
      .filter(c => c.isVideo)
      // 同一张卡的多个 img(封面+头像等)会重复命中,按 card 去重保留首个
      .filter((c, i, arr) => arr.findIndex(o => o.card === c.card) === i)
      .sort((p, q) => (Math.round(p.b.top / 100) - Math.round(q.b.top / 100)) || (p.b.left - q.b.left))

  // 等结果区视频卡渲染稳定再选:连续两次数量相同且 >= index+1 视为稳定。
  // 【慢环境放大】3G 懒加载列表/时长出得很慢,轮询上限从 8×300ms(~2.4s)放大到
  // 24×500ms(~12s 总上限),容忍慢网;一旦"出现 >= index+1 张卡且连续两次稳定"就提前退出,
  // 快网下不会白等。带总上限避免无限循环。
  const MAX_TRIES = 24
  const STEP_MS = 500
  let cards = collectCards()
  let stable = 0
  for (let t = 0; t < MAX_TRIES; t++) {
    const prev = cards.length
    await sleep(STEP_MS)
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
  if (!chosen) {
    return { clicked: false, index: i, total: cards.length, title: '', reason: 'no_video_cover_cards' }
  }

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

  // DOM-only 信任:仅"dispatch 了点击"不等于视频真打开。短轮询确认导航生效(URL 进入 /video/、
  // 或视频播放器节点出现),clicked 才置 true,反映真实打开效果。最多 ~3s,确认不到记 not_navigated。
  const navigated = (): boolean =>
    /\/video\//.test(location.href) ||
    /[?&]modal_id=/.test(location.href) ||
    !!document.querySelector('xg-video-container, .xgplayer, video[src], [data-e2e="feed-active-video"]')
  // 【慢环境放大】点击后视频页打开/播放器渲染在 3G 下也慢,确认窗口从 12×250ms(~3s)
  // 放大到 24×400ms(~9.6s 总上限),避免视频其实已开但渲染慢被误报 clicked_but_not_navigated。
  for (let t = 0; t < 24 && !navigated(); t++) await sleep(400)
  const ok = navigated()
  return {
    clicked: ok,
    index: i,
    total: cards.length,
    title: chosen.title,
    reason: ok ? '' : 'clicked_but_not_navigated',
  }
}
