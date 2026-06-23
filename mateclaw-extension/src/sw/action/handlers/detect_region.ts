import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DetectRegionParams } from '../types'
import type { RegionRegistry } from '../../../runtime/region-registry'

export interface DetectRegionHandlerDeps {
  regions: RegionRegistry
  chrome?: typeof globalThis.chrome
}

export const detectRegionHandler = (deps: DetectRegionHandlerDeps): ActionHandler<DetectRegionParams> => {
  return async (tabId, params, deadlineMs) => {
    if (!isValidParams(params)) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        'detect_region params were malformed',
        false,
      )
    }

    const api = deps.chrome ?? globalThis.chrome
    if (!api?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting is unavailable', true)
    }

    // 页内轮询预算:评论列表异步渲染,后端单次探测易落空。给页内函数一段轮询窗口(留 1.2s 余量
    // 给注入/回传,封顶 3s),命中即返回——成功时零等待,渲染慢时也能等到。
    const pollMs = Math.max(0, Math.min(3000, (deadlineMs ?? 3000) - 1200))
    const results = await api.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: detectRegionInPage,
      args: [params.regionKey, params.strategy ?? 'auto', pollMs, 150],
    })
    const detected = results?.[0]?.result as DetectedRegion | undefined
    // 【临时诊断】把探测真实结果打到 SW 控制台,便于定位 COMMENT_REGION_NOT_FOUND 根因(成功后可移除)。
    try {
      console.log('[mateclaw detect_region]', JSON.stringify({
        tabId,
        ok: detected?.ok ?? false,
        reason: detected?.reason,
        rect: detected?.rect,
        source: detected?.source,
      }))
    } catch { /* ignore */ }
    if (!detected?.ok || !detected.rect) {
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        `region '${params.regionKey}' not detected: ${detected?.reason || 'no result'}`,
        true,
      )
    }

    const region = deps.regions.register({
      key: params.regionKey,
      tabId,
      rect: detected.rect,
      source: detected.source,
    })

    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        regionKey: region.key,
        rect: {
          x: region.x,
          y: region.y,
          width: region.width,
          height: region.height,
        },
        safePoint: detected.safePoint ?? {
          x: region.x + region.width / 2,
          y: region.y + region.height / 2,
        },
        source: detected.source ?? 'dom_detect',
      },
    }
  }
}

interface DetectedRegion {
  ok: boolean
  reason?: string
  rect?: { x: number; y: number; width: number; height: number }
  safePoint?: { x: number; y: number }
  source?: string
}

interface RegionCandidate {
  el: HTMLElement
  rect: DOMRect
  score: number
  reason: string
}

interface CommentSignal {
  el: HTMLElement
  rect: DOMRect
  kind: string
}

function isValidParams(params: DetectRegionParams): boolean {
  return !!params
    && typeof params.regionKey === 'string'
    && params.regionKey.trim().length > 0
    && (params.strategy === undefined || params.strategy === 'auto' || params.strategy === 'dom')
}

/**
 * 自包含的页内探测函数。
 *
 * ⚠️ 铁律:本函数经 chrome.scripting.executeScript({func}) 被 `toString()` 序列化、注入到目标
 * 页面执行 —— 页面里【没有】本模块作用域。因此它【绝不能】引用任何模块级函数/变量,所有辅助函数
 * 必须作为嵌套函数定义在内部(与 scroll_region.ts 的 scrollRegionInPage / douyinCommentRegionState
 * 一致)。早期版本把辅助函数放在模块级,注入后 ReferenceError → 探测必然失败 → COMMENT_REGION_NOT_FOUND
 * (DOM-only 之前后端走 CDP observe 兜底,未暴露;DOM-only 后兜底没了,彻底暴露)。改动本函数务必保持自包含。
 */
async function detectRegionInPage(
  regionKey: string,
  _strategy: string,
  pollMs?: number,
  intervalMs?: number,
): Promise<DetectedRegion> {
  if (regionKey !== 'douyin.comments') {
    return { ok: false, reason: 'unsupported_region_key' }
  }

  const viewport = { width: window.innerWidth || 1, height: window.innerHeight || 1 }

  // ===== 嵌套辅助(必须全部在此函数内,见上方铁律) =====
  function detectDouyinCommentListRegion(viewport: { width: number; height: number }): DetectedRegion | null {
    const list = findVisibleDouyinCommentList(viewport)
    if (!list) return null

    const rawRect = list.getBoundingClientRect()
    const rect = clampRect(rawRect, viewport)
    // 评论很少时列表很矮(1~2 条 ≈ 几十~一百多 px)。直选 [data-e2e=comment-list] 是可靠命中,矮也
    // 确实是评论区,门槛放宽到能容纳"约 1 条评论"的高度;否则少评论视频探不到区域、落空到 observe-tree
    // 兜底(DOM 走树器下不可靠)→ 抛 COMMENT_REGION_NOT_FOUND。候选路(markers/text)门槛不动以防误判。
    if (rect.width < 160 || rect.height < 36) return null

    const itemCount = list.querySelectorAll('[data-e2e="comment-item"]').length
    const bodyRect = bestVisibleCommentBodyRect(list, viewport)
    const safePoint = {
      x: Math.round(rect.x + rect.width * 0.62),
      y: Math.round(rect.y + rect.height * 0.42),
    }
    return {
      ok: true,
      rect,
      safePoint: bodyRect ? {
        x: Math.round(clamp(safePoint.x, rect.x + 18, rect.x + rect.width - 48)),
        y: Math.round(clamp(safePoint.y, rect.y + 42, rect.y + rect.height - 36)),
      } : safePoint,
      source: `dom_detect:comment-list:${itemCount}`,
    }
  }

  function findVisibleDouyinCommentList(viewport: { width: number; height: number }): HTMLElement | null {
    return Array.from(new Set([
      ...Array.from(document.querySelectorAll<HTMLElement>('#merge-all-comment-container [data-e2e="comment-list"]')),
      ...Array.from(document.querySelectorAll<HTMLElement>('[data-e2e="comment-list"]')),
    ]))
      .map(el => ({ el, rect: el.getBoundingClientRect() }))
      // 用宽松可见性:评论很少时列表矮,isVisiblePanelRect 的 height≥120 会把"少评论矮面板"误滤掉,
      // 导致 detect 落空 → COMMENT_REGION_NOT_FOUND。直选 comment-list 可靠,只要在屏内 + 有基本宽高即可。
      .filter(entry => commentListVisibleLoose(entry.rect, viewport))
      .sort((a, b) => {
        const itemDiff = b.el.querySelectorAll('[data-e2e="comment-item"]').length - a.el.querySelectorAll('[data-e2e="comment-item"]').length
        if (itemDiff !== 0) return itemDiff
        return area(b.rect) - area(a.rect)
      })[0]?.el ?? null
  }

  function bestVisibleCommentBodyRect(list: HTMLElement, viewport: { width: number; height: number }): DOMRect | null {
    return Array.from(list.querySelectorAll<HTMLElement>('.Sbe6bqNb, .LqTo7UJT, .LvAtyU_f, [data-e2e*="comment-text" i], [data-e2e*="content" i]'))
      .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanText(el.textContent || '') }))
      .filter(entry => entry.text.length > 0)
      .filter(entry => isVisiblePanelRect(entry.rect, viewport))
      .filter(entry => !entry.el.closest('[data-e2e="video-comment-more"], .comment-reply-expand-btn, .comment-item-stats-container'))
      .sort((a, b) => {
        const aY = a.rect.top + a.rect.height / 2
        const bY = b.rect.top + b.rect.height / 2
        const mid = viewport.height * 0.52
        return Math.abs(aY - mid) - Math.abs(bY - mid)
      })[0]?.rect ?? null
  }

  function findDouyinCommentSignals(viewport: { width: number; height: number }): CommentSignal[] {
    const selectors = [
      'textarea',
      'input',
      '[contenteditable="true"]',
      '[aria-label*="评论"]',
      '[aria-label*="回复"]',
      '[placeholder*="评论"]',
      '[placeholder*="说"]',
      '[data-e2e*="comment" i]',
      '[data-e2e*="reply" i]',
      '[class*="comment" i]',
      '[class*="reply" i]',
      '[role="tab"]',
      'button',
      'h1',
      'h2',
      'h3',
      'span',
      'p',
    ].join(',')

    return Array.from(document.querySelectorAll<HTMLElement>(selectors))
      .map(el => {
        const rect = el.getBoundingClientRect()
        if (!isVisiblePanelRect(rect, viewport)) return null
        const text = cleanText([
          el.getAttribute('aria-label') || '',
          el.getAttribute('placeholder') || '',
          el.textContent || '',
        ].join(' '))
        const marker = elementMarker(el)
        const kind = commentSignalKind(text, marker, el)
        return kind ? { el, rect, kind } : null
      })
      .filter((signal): signal is CommentSignal => signal !== null)
      .filter((signal, index, signals) => signals.findIndex(other => other.el === signal.el) === index)
      .slice(0, 80)
  }

  function commentSignalKind(text: string, marker: string, el: HTMLElement): string | null {
    const compact = text.replace(/\s+/g, '')
    if (marker.includes('comment') || marker.includes('reply')) return 'marker'
    if (compact.includes('全部评论')) return 'all-comments'
    if (/^评论(?:\d+)?$/u.test(compact) || /^评论\d+/u.test(compact)) return 'comment-tab'
    if (compact.includes('说点什么') || compact.includes('发表评论') || compact.includes('留下你的精彩评论')) {
      return 'comment-input'
    }
    if (compact === '回复' || compact.includes('展开回复')) return 'reply'
    if ((el instanceof HTMLTextAreaElement || el instanceof HTMLInputElement) &&
      /评论|回复|说/u.test(`${el.placeholder || ''}${el.getAttribute('aria-label') || ''}`)) {
      return 'comment-input'
    }
    return null
  }

  function commentRegionCandidatesFromSignals(
    signals: CommentSignal[],
    viewport: { width: number; height: number },
  ): RegionCandidate[] {
    const candidates: RegionCandidate[] = []
    for (const signal of signals) {
      let current: HTMLElement | null = signal.el
      let depth = 0
      while (current && current !== document.body && depth < 12) {
        const scored = scoreDouyinCommentsRegion(current, viewport, signal, signals)
        if (scored) candidates.push(scored)
        current = current.parentElement
        depth += 1
      }
    }
    return candidates
  }

  function commentRegionCandidatesFromMarkers(viewport: { width: number; height: number }): RegionCandidate[] {
    const selectors = [
      '[data-e2e*="comment" i]',
      '[data-e2e*="reply" i]',
      '[class*="comment" i]',
      '[class*="reply" i]',
      '[aria-label*="评论"]',
      '[aria-label*="回复"]',
      'aside',
      'section',
      '[role="tabpanel"]',
    ].join(',')
    return Array.from(document.querySelectorAll<HTMLElement>(selectors))
      .slice(0, 160)
      .map(el => scoreDouyinCommentsRegion(el, viewport, undefined, []))
      .filter((entry): entry is RegionCandidate => entry !== null)
  }

  function commentRegionCandidatesFromStrongPanelText(viewport: { width: number; height: number }): RegionCandidate[] {
    const bodyText = cleanText(document.body?.innerText || document.body?.textContent || '')
    if (!hasStrongCommentPanelText(bodyText)) return []

    const candidates: RegionCandidate[] = []
    const seen = new Set<HTMLElement>()
    const add = (el: Element | null | undefined) => {
      if (!(el instanceof HTMLElement) || seen.has(el)) return
      seen.add(el)
      const scored = scoreDouyinCommentsRegion(el, viewport, undefined, [])
      if (scored) candidates.push({
        ...scored,
        score: scored.score + 120,
        reason: `strong-panel-text+${scored.reason}`,
      })
    }

    for (const el of Array.from(document.querySelectorAll<HTMLElement>('aside, section, main, [role="dialog"], [role="tabpanel"], div')).slice(0, 500)) {
      const rect = el.getBoundingClientRect()
      if (rect.left < viewport.width * 0.38) continue
      if (rect.height < viewport.height * 0.45 || rect.width < 260) continue
      const text = cleanText(el.innerText || el.textContent || '')
      if (hasStrongCommentPanelText(text)) add(el)
      for (const child of Array.from(el.querySelectorAll<HTMLElement>('div, section, article, ul, main')).slice(0, 80)) {
        const childRect = child.getBoundingClientRect()
        if (childRect.left < viewport.width * 0.38 || childRect.height < viewport.height * 0.35) continue
        if (child.scrollHeight <= child.clientHeight + 8) continue
        const childText = cleanText(child.innerText || child.textContent || '')
        if (hasStrongCommentPanelText(childText) || childText.includes('回复')) add(child)
      }
    }
    return candidates
  }

  function hasStrongCommentPanelText(text: string): boolean {
    const compact = text.replace(/\s+/g, '')
    return compact.includes('全部评论') ||
      /\d+条评论/u.test(compact) ||
      compact.includes('评论区') ||
      compact.includes('评论详情') ||
      compact.includes('说点什么') ||
      compact.includes('写评论') ||
      compact.includes('发表评论') ||
      compact.includes('暂时没有评论')
  }

  function scoreDouyinCommentsRegion(
    el: HTMLElement,
    viewport: { width: number; height: number },
    signal?: CommentSignal,
    allSignals: CommentSignal[] = [],
  ): RegionCandidate | null {
    const rect = el.getBoundingClientRect()
    if (!isVisiblePanelRect(rect, viewport)) return null

    const text = cleanText(`${el.getAttribute('aria-label') || ''} ${el.getAttribute('placeholder') || ''} ${limitedText(el)}`)
    const marker = elementMarker(el)
    const style = getComputedStyle(el)
    const overflow = `${style.overflow} ${style.overflowY} ${style.overflowX}`.toLowerCase()
    const scrollable = el.scrollHeight > el.clientHeight + 8 || el.scrollWidth > el.clientWidth + 8
    const textHasCommentSignals = text.includes('全部评论') ||
      text.includes('说点什么') ||
      text.includes('写评论') ||
      text.includes('回复') ||
      /评论\s*\d/.test(text)
    const markerHasCommentSignals = marker.includes('comment') || marker.includes('reply')
    const containedSignalCount = allSignals.filter(item => el.contains(item.el)).length

    let score = 0
    const reasons: string[] = []
    if (signal && el.contains(signal.el)) {
      score += 85
      reasons.push(`signal:${signal.kind}`)
    }
    if (containedSignalCount >= 2) {
      score += Math.min(110, containedSignalCount * 35)
      reasons.push(`signals:${containedSignalCount}`)
    }
    if (markerHasCommentSignals) {
      score += 90
      reasons.push('marker')
    }
    if (textHasCommentSignals) {
      score += 80
      reasons.push('text')
    }
    if (scrollable) {
      score += 80
      reasons.push('scrollable')
    }
    if (overflow.includes('auto') || overflow.includes('scroll')) {
      score += 35
      reasons.push('overflow')
    }
    if (rect.left >= viewport.width * 0.42) {
      score += 35
      reasons.push('right-panel')
    }
    if (rect.left >= viewport.width * 0.62 && rect.width >= 260) {
      score += 35
      reasons.push('right-comments-lane')
    }
    if (rect.height >= viewport.height * 0.55) {
      score += 30
      reasons.push('tall')
    }
    if (rect.width >= 300 && rect.width <= Math.max(760, viewport.width * 0.46)) {
      score += 25
      reasons.push('panel-width')
    }
    if (text.includes('点赞') && text.includes('分享') && !textHasCommentSignals) {
      score -= 90
    }
    if (looksLikeWholePage(rect, viewport)) {
      score -= 120
    }
    if (rect.left < viewport.width * 0.35 && rect.width > viewport.width * 0.55) {
      score -= 90
    }
    if (text.length < 8 && !markerHasCommentSignals && !scrollable) {
      score -= 60
    }

    return score > 0 ? { el, rect, score, reason: reasons.join('+') || 'heuristic' } : null
  }

  function isVisiblePanelRect(rect: DOMRect, viewport: { width: number; height: number }): boolean {
    return rect.width >= 160 &&
      rect.height >= 120 &&
      rect.right > 0 &&
      rect.bottom > 0 &&
      rect.left < viewport.width &&
      rect.top < viewport.height
  }

  /**
   * 宽松可见性:仅用于可靠直选 [data-e2e="comment-list"]。评论很少时列表很矮(可能只有几十 px),
   * isVisiblePanelRect 的 height≥120 会把它误判为不可见而过滤掉,导致少评论视频探不到评论区。
   * 这里只要求"在视口内 + 有基本宽高(约能容纳 1 条评论)"。
   */
  function commentListVisibleLoose(rect: DOMRect, viewport: { width: number; height: number }): boolean {
    return rect.width >= 160 &&
      rect.height >= 36 &&
      rect.right > 0 &&
      rect.bottom > 0 &&
      rect.left < viewport.width &&
      rect.top < viewport.height
  }

  function looksLikeWholePage(rect: DOMRect, viewport: { width: number; height: number }): boolean {
    return rect.left <= 8 &&
      rect.top <= 8 &&
      rect.width >= viewport.width * 0.90 &&
      rect.height >= viewport.height * 0.90
  }

  function elementMarker(el: HTMLElement): string {
    return `${el.getAttribute('data-e2e') || ''} ${String(el.className || '')} ${el.getAttribute('aria-label') || ''}`.toLowerCase()
  }

  function limitedText(el: HTMLElement): string {
    const text = el.textContent || ''
    return text.length > 900 ? text.slice(0, 900) : text
  }

  function clampRect(rect: DOMRect, viewport: { width: number; height: number }): { x: number; y: number; width: number; height: number } {
    const left = Math.max(0, Math.min(viewport.width - 1, rect.left))
    const top = Math.max(0, Math.min(viewport.height - 1, rect.top))
    const right = Math.max(left + 1, Math.min(viewport.width, rect.right))
    const bottom = Math.max(top + 1, Math.min(viewport.height, rect.bottom))
    return {
      x: Math.round(left),
      y: Math.round(top),
      width: Math.round(right - left),
      height: Math.round(bottom - top),
    }
  }

  function area(rect: DOMRect): number {
    return Math.max(0, rect.width) * Math.max(0, rect.height)
  }

  function clamp(value: number, min: number, max: number): number {
    return Math.min(max, Math.max(min, value))
  }

  function cleanText(text: string): string {
    return text.replace(/\s+/g, ' ').trim()
  }

  // ===== 编排逻辑 =====
  // 评论列表是异步渲染的:openComments 点开评论图标后,弹层内 [data-e2e="comment-list"] 可能晚
  // 1~3s 才挂载。后端是单次探测、不重试,若此刻还没挂载就落空→COMMENT_REGION_NOT_FOUND。这里在
  // 页内短轮询【可靠路】(只查 comment-list,极轻量),渲染出来即返回,吸收这段渲染竞态。候选/兜底
  // 路(重量级全页扫描)仍只在轮询超时后跑一次,避免每轮重复扫页。
  const budget = typeof pollMs === 'number' && pollMs > 0 ? pollMs : 0
  const step = typeof intervalMs === 'number' && intervalMs > 0 ? intervalMs : 150
  const deadline = Date.now() + budget
  let commentListRegion = detectDouyinCommentListRegion(viewport)
  while (!commentListRegion && Date.now() < deadline) {
    await new Promise<void>(resolve => setTimeout(resolve, step))
    commentListRegion = detectDouyinCommentListRegion(viewport)
  }
  if (commentListRegion) {
    return commentListRegion
  }

  const signals = findDouyinCommentSignals(viewport)
  const candidates = [
    ...commentRegionCandidatesFromSignals(signals, viewport),
    ...commentRegionCandidatesFromMarkers(viewport),
    ...commentRegionCandidatesFromStrongPanelText(viewport),
  ]
    .filter((entry): entry is RegionCandidate => entry !== null && entry.score > 0)
    .filter((entry, index, entries) => entries.findIndex(other => other.el === entry.el) === index)
    .sort((a, b) => b.score - a.score)

  const best = candidates[0]
  if (!best) {
    // 诊断:把评论列表真实情况带回(后端报错/SW 控制台可见),便于区分"评论没开"还是"被过滤"。
    const listCount = document.querySelectorAll('[data-e2e="comment-list"]').length
    const itemCount = document.querySelectorAll('[data-e2e="comment-item"]').length
    return {
      ok: false,
      reason: `douyin_comments_container_not_found:signals=${signals.length};lists=${listCount};items=${itemCount};path=${location.pathname}`,
    }
  }

  const rect = clampRect(best.rect, viewport)
  if (rect.width < 240 || rect.height < 260) {
    return { ok: false, reason: 'detected_region_too_small' }
  }

  return {
    ok: true,
    rect,
    safePoint: {
      x: rect.x + rect.width * 0.72,
      y: rect.y + rect.height * 0.62,
    },
    source: `dom_detect:${best.reason}`,
  }
}
