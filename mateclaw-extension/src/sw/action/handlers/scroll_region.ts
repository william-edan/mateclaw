import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ScrollParams, ScrollRegionParams } from '../types'
import type { RegionRegistry } from '../../../runtime/region-registry'
import { parseScrollRegionParams, toCompatibleScrollParams } from '../../../runtime/scroll-region'
import { DOM_ONLY_NO_CDP_FALLBACK } from './debug-flags'

export interface ScrollRegionHandlerDeps {
  regions: RegionRegistry
  scroll: ActionHandler<ScrollParams>
  chrome?: typeof globalThis.chrome
}

export const scrollRegionHandler = (deps: ScrollRegionHandlerDeps): ActionHandler<ScrollRegionParams> => {
  return async (tabId, params, deadlineMs) => {
    const parsed = parseScrollRegionParams(params)
    if (!parsed) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        'scroll_region params were malformed',
        false,
      )
    }

    const region = deps.regions.get(tabId, parsed.regionKey)
    if (!region) {
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        `no runtime region registered for key '${parsed.regionKey}' in tab ${tabId}`,
        true,
      )
    }

    if (parsed.regionKey === 'douyin.comments') {
      // 后台静默优先 — 快速专用路径(F12 实测得出):
      // 抖音评论列表 [data-e2e="comment-list"] 自身就是滚动容器,直接 scrollTop=scrollHeight
      // 即可触发虚拟列表续拉,且【只滚评论容器、不碰外层视频 feed(#sliderVideo /
      // feed-active-video)】以免误切视频。这条又快又准、不取 innerText(避免重量级扫描超时),
      // executeScript 注入、不依赖窗口活动tab/焦点。找不到评论列表时才回退重量级 DOM / CDP 滚轮。
      const fast = await tryFastDouyinCommentScroll(tabId, deps.chrome, deadlineMs)
      if (fast.ok) {
        return {
          ok: true,
          elapsed_ms: 0,
          payload: {
            regionKey: parsed.regionKey,
            stopWhen: parsed.stopWhen,
            mode: 'dom_douyin_fast',
            ...fast.payload,
          },
        }
      }
      const domComments = await tryDomScrollRegion(
        tabId,
        parsed.regionKey,
        parsed.direction,
        parsed.amount,
        region,
        deps.chrome,
        deadlineMs,
      )
      if (domComments.ok) {
        return {
          ok: true,
          elapsed_ms: 0,
          payload: {
            regionKey: parsed.regionKey,
            stopWhen: parsed.stopWhen,
            mode: 'dom_scroll_container',
            ...domComments.payload,
          },
        }
      }
      // 【临时调试 · DOM_ONLY】抖音评论 DOM 滚动(fast + region)都失败即报错,
      // 不回退 CDP 坐标滚轮,暴露评论采集的 DOM 问题。见 debug-flags.ts。
      if (DOM_ONLY_NO_CDP_FALLBACK) {
        throw new ActionFailureError(
          'HANDLER_ERROR',
          `dom_only_no_cdp: 抖音评论 DOM 滚动失败 (fast:${fast.reason}; region:${domComments.reason})`,
          false,
        )
      }
      return wheelScrollDouyinComments(
        deps,
        tabId,
        parsed.direction,
        parsed.amount,
        parsed.segments,
        parsed.stopWhen,
        region,
        { reason: `dom_fast:${fast.reason};dom_region:${domComments.reason}` },
        deadlineMs,
      )
    }

    const domResult = await tryDomScrollRegion(
      tabId,
      parsed.regionKey,
      parsed.direction,
      parsed.amount,
      region,
      deps.chrome,
      deadlineMs,
    )
    if (domResult.ok) {
      return {
        ok: true,
        elapsed_ms: 0,
        payload: {
          regionKey: parsed.regionKey,
          stopWhen: parsed.stopWhen,
          mode: 'dom_scroll_container',
          ...domResult.payload,
        },
      }
    }

    // 【临时调试 · DOM_ONLY】区域 DOM 滚动失败即报错,不回退 CDP 滚轮。见 debug-flags.ts。
    if (DOM_ONLY_NO_CDP_FALLBACK) {
      throw new ActionFailureError(
        'HANDLER_ERROR',
        `dom_only_no_cdp: 区域 DOM 滚动失败 region=${parsed.regionKey}; reason=${domResult.reason}`,
        false,
      )
    }

    const compatible = toCompatibleScrollParams(parsed, region)
    const result = await deps.scroll(tabId, compatible, deadlineMs)

    if (result.ok !== true) return result
    return {
      ...result,
      payload: {
        ...result.payload,
        regionKey: parsed.regionKey,
        stopWhen: parsed.stopWhen,
        mode: 'wheel_fallback',
        reason: domResult.reason,
        wheelX: compatible.x,
        wheelY: compatible.y,
      },
    }
  }
}

async function wheelScrollDouyinComments(
  deps: ScrollRegionHandlerDeps,
  tabId: number,
  direction: ScrollRegionParams['direction'],
  amount: number,
  segments: number | undefined,
  stopWhen: ScrollRegionParams['stopWhen'] | undefined,
  region: { x: number; y: number; width: number; height: number },
  domResult: { reason: string; payload?: Record<string, unknown> },
  deadlineMs: number | undefined,
) {
  const before = await captureDouyinCommentState(tabId, region, deps.chrome, deadlineMs)
  if (before && before.hasCommentPanel === false) {
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        ...(domResult.payload ?? {}),
        regionKey: 'douyin.comments',
        stopWhen,
        mode: 'comment_region_wheel',
        moved: false,
        reason: 'comment_panel_not_verified_before_wheel',
        domReason: domResult.reason,
        before,
      },
    }
  }

  const expanded = { expanded: 0, disabled: true }

  const points = douyinCommentWheelPoints(region, before?.commentListRect, before?.commentBodyRect)
  let lastResult: Awaited<ReturnType<ActionHandler<ScrollParams>>> | null = null
  let lastAfter: DouyinCommentRegionState | null = null
  for (const point of points) {
    lastResult = await deps.scroll(tabId, {
      direction,
      distance_px: amount,
      segments: segments ?? 5,
      x: point.x,
      y: point.y,
    }, deadlineMs)
    if (lastResult.ok !== true) {
      return lastResult
    }
    await sleep(260)
    const after = await captureDouyinCommentState(tabId, region, deps.chrome, deadlineMs)
    lastAfter = after
    const evidence = commentWheelEvidence(parsedDirection(direction), before, after)
    if (evidence.moved) {
      return {
        ok: true,
        elapsed_ms: 0,
        payload: {
          ...(domResult.payload ?? {}),
          regionKey: 'douyin.comments',
          stopWhen,
          mode: 'comment_region_wheel',
          moved: true,
          reason: evidence.reason,
          forwardProgress: evidence.forwardProgress,
          newVisibleItemCount: evidence.newVisibleItemCount,
          retainedVisibleItemCount: evidence.retainedVisibleItemCount,
          domReason: domResult.reason,
          expandedReplyButtons: expanded.expanded,
          wheelX: point.x,
          wheelY: point.y,
          before,
          after,
          ...commentWindowPayload(before, after, evidence),
        },
      }
    }
    if (evidence.reason === 'url_changed_or_panel_lost') {
      return {
        ok: true,
        elapsed_ms: 0,
        payload: {
          ...(domResult.payload ?? {}),
          regionKey: 'douyin.comments',
          stopWhen,
          mode: 'comment_region_wheel',
          moved: false,
          reason: evidence.reason,
          domReason: domResult.reason,
          expandedReplyButtons: expanded.expanded,
          wheelX: point.x,
          wheelY: point.y,
          before,
          after,
          ...commentWindowPayload(before, after, evidence),
        },
      }
    }
  }

  return {
    ok: true,
    elapsed_ms: 0,
    payload: {
      ...(domResult.payload ?? {}),
      regionKey: 'douyin.comments',
      stopWhen,
      mode: 'comment_region_wheel',
      moved: false,
      reason: 'comment_region_wheel_not_moved',
      domReason: domResult.reason,
      expandedReplyButtons: expanded.expanded,
      before,
      after: lastAfter,
      ...commentWindowPayload(before, lastAfter, {
        moved: false,
        reason: 'comment_region_wheel_not_moved',
        forwardProgress: false,
        newVisibleItemCount: 0,
        retainedVisibleItemCount: retainedItemCount(before?.itemSignatures ?? [], lastAfter?.itemSignatures ?? []),
      }),
      scrollResult: lastResult?.payload ?? {},
    },
  }
}

interface DouyinCommentRegionState {
  href: string
  hasCommentPanel: boolean
  signature: string
  visibleTextCount: number
  visibleItemCount: number
  itemSignatures: string[]
  firstItemSignature?: string
  lastItemSignature?: string
  windowSignature?: string
  scrollTop: number | null
  scrollHeight: number | null
  clientHeight: number | null
  commentListRect?: { x: number; y: number; width: number; height: number }
  commentBodyRect?: { x: number; y: number; width: number; height: number }
  containerE2E?: string
  containerTag?: string
  containerClass?: string
}

async function captureDouyinCommentState(
  tabId: number,
  region: { x: number; y: number; width: number; height: number },
  chromeApi: typeof globalThis.chrome | undefined,
  deadlineMs: number | undefined,
): Promise<DouyinCommentRegionState | null> {
  const api = chromeApi ?? globalThis.chrome
  if (!api?.scripting?.executeScript) return null
  const timeoutMs = Math.max(350, Math.min(900, deadlineMs ? Math.floor(deadlineMs * 0.25) : 650))
  try {
    const results = await withTimeout(api.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: douyinCommentRegionState,
      args: [region],
    }), timeoutMs)
    return (results?.[0]?.result ?? null) as DouyinCommentRegionState | null
  } catch {
    return null
  }
}

function douyinCommentWheelPoints(
  region: { x: number; y: number; width: number; height: number },
  commentListRect?: { x: number; y: number; width: number; height: number },
  commentBodyRect?: { x: number; y: number; width: number; height: number },
): Array<{ x: number; y: number }> {
  const source = commentListRect && commentListRect.width >= 160 && commentListRect.height >= 120
    ? commentListRect
    : region
  const left = source.x
  const top = source.y
  const width = Math.max(1, source.width)
  const height = Math.max(1, source.height)
  const points = [
    { x: left + width * 0.62, y: top + height * 0.42 },
  ]
  const minX = left + Math.min(24, Math.max(8, width * 0.12))
  const maxX = Math.max(minX, left + width - Math.min(96, Math.max(24, width * 0.24)))
  return points.map(point => ({
    x: Math.round(clamp(point.x, minX, maxX)),
    y: Math.round(clamp(point.y, top + 36, top + height - 24)),
  }))
}

function commentWheelEvidence(
  direction: ScrollRegionParams['direction'],
  before: DouyinCommentRegionState | null,
  after: DouyinCommentRegionState | null,
): { moved: boolean; reason: string; forwardProgress: boolean; newVisibleItemCount: number; retainedVisibleItemCount: number } {
  if (!after) return emptyEvidence('state_unavailable_after_wheel')
  if (after.hasCommentPanel === false) return emptyEvidence('url_changed_or_panel_lost')
  if (before && before.href && after.href && before.href !== after.href) {
    return emptyEvidence('url_changed_or_panel_lost')
  }

  const beforeItems = before?.itemSignatures ?? []
  const afterItems = after.itemSignatures ?? []
  if (beforeItems.length > 0 && afterItems.length > 0) {
    const itemWindow = itemWindowEvidence(direction, beforeItems, afterItems)
    if (itemWindow.changed) {
      return {
        moved: true,
        reason: itemWindow.forwardProgress ? 'comment_window_advanced' : 'comment_window_changed_without_forward_progress',
        forwardProgress: itemWindow.forwardProgress,
        newVisibleItemCount: itemWindow.newVisibleItemCount,
        retainedVisibleItemCount: itemWindow.retainedVisibleItemCount,
      }
    }
    return {
      moved: false,
      reason: 'comment_window_unchanged',
      forwardProgress: false,
      newVisibleItemCount: 0,
      retainedVisibleItemCount: afterItems.length,
    }
  }

  if (before && after.signature && before.signature !== after.signature) {
    return {
      moved: true,
      reason: 'visible_text_signature_changed_without_item_window',
      forwardProgress: false,
      newVisibleItemCount: 0,
      retainedVisibleItemCount: 0,
    }
  }
  if (before?.scrollTop !== null && before?.scrollTop !== undefined
    && after.scrollTop !== null && after.scrollTop !== undefined
    && Math.abs(after.scrollTop - before.scrollTop) > 1) {
    return emptyEvidence('scroll_top_changed_without_visible_comment_change')
  }
  return emptyEvidence('comment_region_wheel_no_visible_change')
}

function emptyEvidence(reason: string): { moved: boolean; reason: string; forwardProgress: boolean; newVisibleItemCount: number; retainedVisibleItemCount: number } {
  return { moved: false, reason, forwardProgress: false, newVisibleItemCount: 0, retainedVisibleItemCount: 0 }
}

function itemWindowEvidence(
  direction: ScrollRegionParams['direction'],
  beforeItems: string[],
  afterItems: string[],
): { changed: boolean; forwardProgress: boolean; newVisibleItemCount: number; retainedVisibleItemCount: number } {
  const before = compactSignatures(beforeItems)
  const after = compactSignatures(afterItems)
  const changed = before.join('|') !== after.join('|')
  const beforeSet = new Set(before)
  const newVisibleItemCount = after.filter(item => !beforeSet.has(item)).length
  const retainedVisibleItemCount = retainedItemCount(before, after)
  if (!changed) {
    return { changed: false, forwardProgress: false, newVisibleItemCount: 0, retainedVisibleItemCount: after.length }
  }
  if (direction === 'up') {
    return { changed: true, forwardProgress: true, newVisibleItemCount, retainedVisibleItemCount }
  }
  if (direction !== 'down') {
    return { changed: true, forwardProgress: newVisibleItemCount > 0, newVisibleItemCount, retainedVisibleItemCount }
  }

  const afterFirstInBefore = before.indexOf(after[0] ?? '')
  const beforeLastInAfter = after.indexOf(before[before.length - 1] ?? '')
  const droppedTopItems = afterFirstInBefore > 0
  const appendedAfterPreviousLast = beforeLastInAfter >= 0 && beforeLastInAfter < after.length - 1
  const mostlyNewWindow = newVisibleItemCount > 0 && retainedVisibleItemCount <= Math.max(1, Math.floor(after.length * 0.5))
  const forwardProgress = droppedTopItems || appendedAfterPreviousLast || mostlyNewWindow
  return { changed: true, forwardProgress, newVisibleItemCount, retainedVisibleItemCount }
}

function compactSignatures(items: string[]): string[] {
  return items.map(item => item.trim()).filter(Boolean)
}

function retainedItemCount(beforeItems: string[], afterItems: string[]): number {
  const before = new Set(compactSignatures(beforeItems))
  return compactSignatures(afterItems).filter(item => before.has(item)).length
}

function commentWindowPayload(
  before: DouyinCommentRegionState | null,
  after: DouyinCommentRegionState | null,
  evidence: { forwardProgress: boolean; newVisibleItemCount: number; retainedVisibleItemCount: number },
): Record<string, unknown> {
  return {
    forwardProgress: evidence.forwardProgress,
    visibleItemCount: after?.visibleItemCount ?? 0,
    beforeFirstItemSignature: before?.firstItemSignature,
    beforeLastItemSignature: before?.lastItemSignature,
    afterFirstItemSignature: after?.firstItemSignature,
    afterLastItemSignature: after?.lastItemSignature,
    beforeWindowSignature: before?.windowSignature,
    afterWindowSignature: after?.windowSignature,
    newVisibleItemCount: evidence.newVisibleItemCount,
    retainedVisibleItemCount: evidence.retainedVisibleItemCount,
  }
}

function parsedDirection(direction: ScrollRegionParams['direction']): ScrollRegionParams['direction'] {
  return direction
}

function douyinCommentRegionState(region: { x: number; y: number; width: number; height: number }): DouyinCommentRegionState {
  const clip = {
    left: region.x,
    top: region.y,
    right: region.x + region.width,
    bottom: region.y + region.height,
  }
  const commentList = findVisibleCommentList(clip)
  const commentListRect = commentList?.getBoundingClientRect()
  const stateClip = commentListRect
    ? { left: commentListRect.left, top: commentListRect.top, right: commentListRect.right, bottom: commentListRect.bottom }
    : clip
  const commentBodyRect = commentList ? bestVisibleCommentBodyRect(commentList) : null
  const commentItems = commentList ? visibleCommentItems(commentList, stateClip) : []
  const textRoot: ParentNode = commentList ?? document
  const textEntries = Array.from(textRoot.querySelectorAll<HTMLElement>('span, p, div, a, button'))
    .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanText(el.innerText || el.textContent || '') }))
    .filter(entry => entry.text && mostlyInside(entry.rect, stateClip))
  const visibleTexts = textEntries
    .map(entry => entry.text.replace(/\s+/g, ''))
    .filter(text => text.length >= 2)
    .filter(text => !/^(点赞|分享|收藏|评论|回复|展开|展开回复|详情|TA的作品|问AI)$/.test(text))
    .slice(0, 18)
  const itemSignatures = commentItems.map(item => item.signature)
  const windowSignature = itemSignatures.join('|').slice(0, 900)
  const hasCommentPanel = Boolean(commentList) || textEntries.some(entry => /全部评论|说点什么|发表评论|展开回复|回复|暂时没有评论/u.test(entry.text))
  const scrollRoot = commentList
    ? bestCommentListScrollRoot(commentList, clip)
    : bestScrollContainer(textEntries.map(entry => entry.el), clip)
  return {
    href: location.href,
    hasCommentPanel,
    signature: (windowSignature || visibleTexts.join('|')).slice(0, 900),
    visibleTextCount: visibleTexts.length,
    visibleItemCount: itemSignatures.length,
    itemSignatures,
    firstItemSignature: itemSignatures[0],
    lastItemSignature: itemSignatures[itemSignatures.length - 1],
    windowSignature,
    scrollTop: scrollRoot ? scrollRoot.scrollTop : null,
    scrollHeight: scrollRoot ? scrollRoot.scrollHeight : null,
    clientHeight: scrollRoot ? scrollRoot.clientHeight : null,
    commentListRect: commentListRect ? {
      x: Math.round(commentListRect.x),
      y: Math.round(commentListRect.y),
      width: Math.round(commentListRect.width),
      height: Math.round(commentListRect.height),
    } : undefined,
    commentBodyRect: commentBodyRect ? {
      x: Math.round(commentBodyRect.x),
      y: Math.round(commentBodyRect.y),
      width: Math.round(commentBodyRect.width),
      height: Math.round(commentBodyRect.height),
    } : undefined,
    containerE2E: commentList ? 'comment-list' : scrollRoot?.getAttribute('data-e2e') || undefined,
    containerTag: scrollRoot?.tagName.toLowerCase(),
    containerClass: scrollRoot ? String(scrollRoot.className || '').slice(0, 120) : undefined,
  }

  function findVisibleCommentList(regionClip: { left: number; top: number; right: number; bottom: number }): HTMLElement | null {
    const listSet = new Set<HTMLElement>()
    for (const selector of [
      '#merge-all-comment-container [data-e2e="comment-list"]',
      '[data-e2e="comment-list"]',
    ]) {
      for (const el of Array.from(document.querySelectorAll<HTMLElement>(selector))) {
        listSet.add(el)
      }
    }
    return Array.from(listSet)
      .map(el => ({ el, rect: el.getBoundingClientRect() }))
      .filter(entry => entry.rect.width > 0 && entry.rect.height > 0)
      .filter(entry => intersects(entry.rect, regionClip) || containsClip(entry.rect, regionClip))
      .sort((a, b) => {
        const itemDiff = b.el.querySelectorAll('[data-e2e="comment-item"]').length - a.el.querySelectorAll('[data-e2e="comment-item"]').length
        if (itemDiff !== 0) return itemDiff
        return overlapAreaWithClip(b.rect, regionClip) - overlapAreaWithClip(a.rect, regionClip)
      })[0]?.el ?? null
  }

  function bestCommentListScrollRoot(list: HTMLElement, regionClip: { left: number; top: number; right: number; bottom: number }): HTMLElement | null {
    const candidates: HTMLElement[] = []
    let current: HTMLElement | null = list
    let depth = 0
    while (current && current !== document.body && depth++ < 10) {
      candidates.push(current)
      current = current.parentElement
    }
    return candidates
      .filter(el => el.scrollHeight > el.clientHeight + 8)
      .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanText(el.innerText || el.textContent || '') }))
      .filter(entry => intersects(entry.rect, regionClip) || containsClip(entry.rect, regionClip))
      .sort((a, b) => scoreContainer(b.el, b.rect, b.text, regionClip) - scoreContainer(a.el, a.rect, a.text, regionClip))[0]?.el ?? list
  }

  function bestScrollContainer(seeds: HTMLElement[], regionClip: { left: number; top: number; right: number; bottom: number }): HTMLElement | null {
    const seen = new Set<HTMLElement>()
    const candidates: HTMLElement[] = []
    const addAncestors = (seed: HTMLElement) => {
      let current: HTMLElement | null = seed
      let depth = 0
      while (current && depth++ < 10) {
        if (!seen.has(current)) {
          seen.add(current)
          candidates.push(current)
        }
        current = current.parentElement
      }
    }
    seeds.forEach(addAncestors)
    return candidates
      .filter(el => el.scrollHeight > el.clientHeight + 8)
      .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanText(el.innerText || el.textContent || '') }))
      .filter(entry => intersects(entry.rect, regionClip) || containsClip(entry.rect, regionClip))
      .sort((a, b) => scoreContainer(b.el, b.rect, b.text, regionClip) - scoreContainer(a.el, a.rect, a.text, regionClip))[0]?.el ?? null
  }

  function bestVisibleCommentBodyRect(list: HTMLElement): DOMRect | null {
    return Array.from(list.querySelectorAll<HTMLElement>('.Sbe6bqNb, .LqTo7UJT, .LvAtyU_f, [data-e2e*="comment-text" i], [data-e2e*="content" i]'))
      .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanText(el.textContent || '') }))
      .filter(entry => entry.text.length > 0)
      .filter(entry => mostlyInside(entry.rect, stateClip))
      .filter(entry => !entry.el.closest('[data-e2e="video-comment-more"], .comment-reply-expand-btn, .comment-item-stats-container'))
      .sort((a, b) => {
        const mid = stateClip.top + (stateClip.bottom - stateClip.top) * 0.56
        const ay = a.rect.top + a.rect.height / 2
        const by = b.rect.top + b.rect.height / 2
        return Math.abs(ay - mid) - Math.abs(by - mid)
      })[0]?.rect ?? null
  }

  function visibleCommentItems(
    list: HTMLElement,
    regionClip: { left: number; top: number; right: number; bottom: number },
  ): Array<{ signature: string; top: number; bottom: number }> {
    return Array.from(list.querySelectorAll<HTMLElement>('[data-e2e="comment-item"]'))
      .map((item, index) => {
        const rect = item.getBoundingClientRect()
        if (rect.width <= 0 || rect.height <= 0 || !intersects(rect, regionClip)) return null
        const author = cleanText(
          item.querySelector<HTMLElement>('a[href*="/user/"] [data-click-from="title"], a[href*="/user/"], a[href*="douyin.com/user"]')?.textContent || '',
        )
        const bodyEl = Array.from(item.querySelectorAll<HTMLElement>('.Sbe6bqNb, .LqTo7UJT, .LvAtyU_f, [data-e2e*="comment-text" i], [data-e2e*="content" i]'))
          .filter(el => !el.closest('[data-e2e="video-comment-more"], .comment-reply-expand-btn, .comment-item-stats-container'))
          .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanCommentText(el.textContent || '') }))
          .filter(entry => entry.text && intersects(entry.rect, regionClip))
          .sort((a, b) => b.text.length - a.text.length)[0]?.el
        const body = cleanCommentText(bodyEl?.textContent || item.textContent || '')
        const signature = normalizeSignature(`${author}:${body || index}`)
        if (!signature || signature.length < 2) return null
        return {
          signature,
          top: Math.round(rect.top),
          bottom: Math.round(rect.bottom),
        }
      })
      .filter((item): item is { signature: string; top: number; bottom: number } => item !== null)
      .filter((item, index, arr) => arr.findIndex(other => other.signature === item.signature) === index)
      .slice(0, 16)
  }

  function cleanCommentText(text: string): string {
    return cleanText(text)
      .replace(/\s*(作者回复过|作者)$/u, '')
      .replace(/\s*(回复|展开\d*条?回复|展开回复|点赞|分享)$/u, '')
      .trim()
  }

  function normalizeSignature(text: string): string {
    return cleanText(text).replace(/\s+/g, '').slice(0, 180)
  }

  function scoreContainer(el: HTMLElement, rect: DOMRect, text: string, regionClip: { left: number; top: number; right: number; bottom: number }): number {
    const marker = `${el.getAttribute('data-e2e') || ''} ${el.className || ''} ${el.getAttribute('aria-label') || ''}`.toLowerCase()
    let score = 0
    if (marker.includes('comment') || marker.includes('reply')) score += 80
    if (/全部评论|说点什么|回复/u.test(text)) score += 60
    score += Math.min(90, Math.round((el.scrollHeight - el.clientHeight) / 18))
    score += Math.round(overlapAreaWithClip(rect, regionClip) / 900)
    if (rect.left < regionClip.left - 80) score -= 40
    if (rect.width > (regionClip.right - regionClip.left) * 1.6) score -= 50
    return score
  }

  function cleanText(text: string): string {
    return text.replace(/\s+/g, ' ').trim()
  }

  function mostlyInside(rect: DOMRect, regionClip: { left: number; top: number; right: number; bottom: number }): boolean {
    if (rect.width <= 0 || rect.height <= 0) return false
    const centerX = rect.left + rect.width / 2
    const centerY = rect.top + rect.height / 2
    return centerX >= regionClip.left && centerX <= regionClip.right
      && centerY >= regionClip.top && centerY <= regionClip.bottom
  }

  function intersects(rect: DOMRect, regionClip: { left: number; top: number; right: number; bottom: number }): boolean {
    return rect.right >= regionClip.left && rect.left <= regionClip.right && rect.bottom >= regionClip.top && rect.top <= regionClip.bottom
  }

  function containsClip(rect: DOMRect, regionClip: { left: number; top: number; right: number; bottom: number }): boolean {
    return rect.left <= regionClip.left + 24 && rect.right >= regionClip.right - 24
      && rect.top <= regionClip.top + 80 && rect.bottom >= regionClip.bottom - 80
  }

  function overlapAreaWithClip(rect: DOMRect, regionClip: { left: number; top: number; right: number; bottom: number }): number {
    const left = Math.max(rect.left, regionClip.left)
    const right = Math.min(rect.right, regionClip.right)
    const top = Math.max(rect.top, regionClip.top)
    const bottom = Math.min(rect.bottom, regionClip.bottom)
    return Math.max(0, right - left) * Math.max(0, bottom - top)
  }
}

async function expandVisibleDouyinReplies(
  tabId: number,
  region: { x: number; y: number; width: number; height: number },
  chromeApi: typeof globalThis.chrome | undefined,
  deadlineMs: number | undefined,
): Promise<{ expanded: number }> {
  const api = chromeApi ?? globalThis.chrome
  if (!api?.scripting?.executeScript) return { expanded: 0 }
  const timeoutMs = Math.max(350, Math.min(900, deadlineMs ? Math.floor(deadlineMs * 0.25) : 650))
  try {
    const results = await withTimeout(api.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: expandVisibleDouyinRepliesInPage,
      args: [region, 2],
    }), timeoutMs)
    const payload = results?.[0]?.result as { expanded?: number } | undefined
    return { expanded: Math.max(0, Math.floor(payload?.expanded ?? 0)) }
  } catch {
    return { expanded: 0 }
  }
}

function expandVisibleDouyinRepliesInPage(
  region: { x: number; y: number; width: number; height: number },
  limit: number,
): { expanded: number } {
  const clip = {
    left: region.x,
    top: region.y,
    right: region.x + region.width,
    bottom: region.y + region.height,
  }
  const commentList = Array.from(new Set([
    ...Array.from(document.querySelectorAll<HTMLElement>('#merge-all-comment-container [data-e2e="comment-list"]')),
    ...Array.from(document.querySelectorAll<HTMLElement>('[data-e2e="comment-list"]')),
  ]))
    .map(el => ({ el, rect: el.getBoundingClientRect() }))
    .filter(entry => entry.rect.width > 0 && entry.rect.height > 0)
    .filter(entry => intersects(entry.rect, clip) || overlapAreaWithClip(entry.rect, clip) > 0)
    .sort((a, b) => b.el.querySelectorAll('[data-e2e="comment-item"]').length - a.el.querySelectorAll('[data-e2e="comment-item"]').length)[0]?.el
  if (!commentList) return { expanded: 0 }
  const buttons = Array.from(commentList.querySelectorAll<HTMLButtonElement>('button.comment-reply-expand-btn, .comment-reply-expand-btn'))
    .map(button => ({ button, rect: button.getBoundingClientRect(), text: cleanText(button.textContent || '') }))
    .filter(entry => entry.rect.width > 0 && entry.rect.height > 0)
    .filter(entry => intersects(entry.rect, clip))
    .filter(entry => /^展开\d*条?回复$/u.test(entry.text.replace(/\s+/g, '')))
    .filter(entry => !entry.button.closest('[data-e2e="video-comment-more"], .comment-item-stats-container, [class*="share" i]'))
    .sort((a, b) => a.rect.top - b.rect.top)
    .slice(0, Math.max(0, Math.min(4, Math.floor(limit))))
  let expanded = 0
  for (const entry of buttons) {
    try {
      entry.button.click()
      expanded += 1
    } catch {
      // Ignore an individual detached/stale reply button; the next scroll pass will retry visible buttons.
    }
  }
  return { expanded }

  function cleanText(text: string): string {
    return text.replace(/\s+/g, ' ').trim()
  }

  function intersects(rect: DOMRect, regionClip: { left: number; top: number; right: number; bottom: number }): boolean {
    return rect.right >= regionClip.left && rect.left <= regionClip.right && rect.bottom >= regionClip.top && rect.top <= regionClip.bottom
  }

  function overlapAreaWithClip(rect: DOMRect, regionClip: { left: number; top: number; right: number; bottom: number }): number {
    const left = Math.max(rect.left, regionClip.left)
    const right = Math.min(rect.right, regionClip.right)
    const top = Math.max(rect.top, regionClip.top)
    const bottom = Math.min(rect.bottom, regionClip.bottom)
    return Math.max(0, right - left) * Math.max(0, bottom - top)
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

async function sleep(ms: number): Promise<void> {
  await new Promise<void>(resolve => setTimeout(resolve, Math.max(0, ms)))
}

/**
 * 抖音评论专用快速滚动(F12 实测方案)。只对评论列表容器
 * [data-e2e="comment-list"](其自身 scrollHeight>clientHeight,即真正的滚动容器)
 * 做 scrollTop=scrollHeight,触发虚拟列表续拉;绝不滚动外层视频 feed(#sliderVideo /
 * feed-active-video),以免误切视频。极轻量(不取 innerText、不扫全页),executeScript
 * 注入、后台/最小化可用。找到评论列表即返回 ok:true(续拉是异步的,由网络抓取判定进度)。
 */
async function tryFastDouyinCommentScroll(
  tabId: number,
  chromeApi: typeof globalThis.chrome | undefined,
  deadlineMs: number | undefined,
): Promise<{ ok: true; payload: Record<string, unknown> } | { ok: false; reason: string }> {
  const api = chromeApi ?? globalThis.chrome
  if (!api?.scripting?.executeScript) return { ok: false, reason: 'scripting_unavailable' }
  const timeoutMs = Math.max(300, Math.min(900, deadlineMs ?? 800))
  try {
    const results = await withTimeout(api.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: fastScrollDouyinCommentsInPage,
      args: [],
    }), timeoutMs)
    const payload = results?.[0]?.result as ({ ok?: boolean; reason?: string } & Record<string, unknown>) | undefined
    if (payload?.ok) return { ok: true, payload }
    return { ok: false, reason: payload?.reason || 'fast_scroll_failed' }
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : 'fast_scroll_exception' }
  }
}

/** 注入页内执行:把抖音评论列表滚到底触发续拉。纯 DOM、不碰视频 feed。 */
function fastScrollDouyinCommentsInPage(): Record<string, unknown> {
  const list = (document.querySelector('#merge-all-comment-container [data-e2e="comment-list"]')
    || document.querySelector('[data-e2e="comment-list"]')) as HTMLElement | null
  if (!list) return { ok: false, reason: 'comment_list_not_found' }
  const isVideoFeed = (el: Element): boolean =>
    el.id === 'sliderVideo' || el.getAttribute('data-e2e') === 'feed-active-video'
  // 选定滚动容器:优先评论列表自身;若它本身不可滚,向上找最近可滚祖先,但跳过视频 feed。
  let scroller: HTMLElement = list
  if (!(list.scrollHeight > list.clientHeight + 4)) {
    let cur: HTMLElement | null = list.parentElement
    let depth = 0
    while (cur && depth++ < 12) {
      if (!isVideoFeed(cur) && cur.scrollHeight > cur.clientHeight + 4) { scroller = cur; break }
      cur = cur.parentElement
    }
  }
  if (isVideoFeed(scroller)) return { ok: false, reason: 'only_video_feed_scrollable' }
  const beforeItems = list.querySelectorAll('[data-e2e="comment-item"]').length
  const before = scroller.scrollTop
  // 实测有效:直接滚到底触发续拉(scrollBy 会滚但不一定续拉,dispatchWheel 抖音忽略)
  scroller.scrollTop = scroller.scrollHeight
  try { scroller.dispatchEvent(new Event('scroll', { bubbles: true })) } catch { /* ignore */ }
  const after = scroller.scrollTop
  return {
    ok: true,
    moved: Math.abs(after - before) > 1,
    scrollTopBefore: before,
    scrollTopAfter: after,
    scrollHeight: scroller.scrollHeight,
    clientHeight: scroller.clientHeight,
    beforeItems,
    containerE2E: scroller === list ? 'comment-list' : (scroller.getAttribute('data-e2e') || scroller.tagName.toLowerCase()),
  }
}

async function tryDomScrollRegion(
  tabId: number,
  regionKey: string,
  direction: ScrollRegionParams['direction'],
  amount: number,
  region: { x: number; y: number; width: number; height: number },
  chromeApi: typeof globalThis.chrome | undefined,
  deadlineMs: number | undefined,
): Promise<{ ok: true; payload: Record<string, unknown> } | { ok: false; reason: string; payload?: Record<string, unknown> }> {
  const api = chromeApi ?? globalThis.chrome
  if (!api?.scripting?.executeScript) return { ok: false, reason: 'scripting_unavailable' }
  const timeoutMs = Math.max(350, Math.min(1_200, deadlineMs ? Math.floor(deadlineMs * 0.45) : 700))
  try {
    const results = await withTimeout(api.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: scrollRegionInPage,
      args: [regionKey, direction, amount, region],
    }), timeoutMs)
    const payload = results?.[0]?.result as { ok?: boolean; reason?: string } & Record<string, unknown> | undefined
    if (payload?.ok === true) return { ok: true, payload }
    return { ok: false, reason: payload?.reason || 'dom_scroll_failed', payload }
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : 'dom_scroll_exception' }
  }
}

async function withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      promise,
      new Promise<T>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`dom_scroll_timeout_${timeoutMs}ms`)), timeoutMs)
      }),
    ])
  } finally {
    if (timer) clearTimeout(timer)
  }
}

function scrollRegionInPage(
  regionKey: string,
  direction: ScrollRegionParams['direction'],
  amount: number,
  region: { x: number; y: number; width: number; height: number },
): Record<string, unknown> {
  const regionRect = {
    left: region.x,
    top: region.y,
    right: region.x + region.width,
    bottom: region.y + region.height,
  }
  const scrollRoot = regionKey === 'douyin.comments'
    ? findDouyinCommentListScrollRoot(regionRect) ?? findScrollContainerFromRegionPoints(regionRect)
    : findScrollContainer(regionKey, regionRect)
  if (!scrollRoot) return { ok: false, reason: 'scroll_container_not_found' }

  const commentList = regionKey === 'douyin.comments' ? findVisibleDouyinCommentList(regionRect) : null
  const beforeTop = scrollRoot.scrollTop
  const beforeLeft = scrollRoot.scrollLeft
  const beforeSignature = regionKey === 'douyin.comments' ? visibleCommentSignature(commentList) : ''
  const delta = Math.max(1, Math.round(Math.abs(amount)))
  const dx = direction === 'left' ? -delta : direction === 'right' ? delta : 0
  const dy = direction === 'up' ? -delta : direction === 'down' ? delta : 0
  const applied = regionKey === 'douyin.comments'
    ? scrollDouyinCommentTargets(scrollRoot, commentList, dx, dy, regionRect)
    : [{ target: scrollRoot, beforeTop, beforeLeft, afterTop: scrollTopAfter(scrollRoot, dx, dy), afterLeft: scrollRoot.scrollLeft, method: 'scrollBy' }]
  const afterTop = scrollRoot.scrollTop
  const afterLeft = scrollRoot.scrollLeft
  const afterSignature = regionKey === 'douyin.comments' ? visibleCommentSignature(commentList) : ''
  const moved = Math.abs(afterTop - beforeTop) > 0 ||
    Math.abs(afterLeft - beforeLeft) > 0 ||
    applied.some(item => Math.abs(item.afterTop - item.beforeTop) > 0 || Math.abs(item.afterLeft - item.beforeLeft) > 0) ||
    Boolean(beforeSignature && afterSignature && beforeSignature !== afterSignature)

  return {
    ok: moved,
    reason: moved ? undefined : 'scroll_container_not_moved',
    moved,
    scrollTopBefore: beforeTop,
    scrollTopAfter: afterTop,
    scrollLeftBefore: beforeLeft,
    scrollLeftAfter: afterLeft,
    scrollHeight: scrollRoot.scrollHeight,
    clientHeight: scrollRoot.clientHeight,
    containerTag: scrollRoot.tagName.toLowerCase(),
    containerE2E: commentList ? 'comment-list' : scrollRoot.getAttribute('data-e2e') || undefined,
    containerClass: String((scrollRoot as HTMLElement).className || '').slice(0, 120),
    commentSignatureBefore: beforeSignature || undefined,
    commentSignatureAfter: afterSignature || undefined,
    appliedScrolls: applied.map(item => ({
      method: item.method,
      tag: item.target.tagName.toLowerCase(),
      dataE2E: item.target.getAttribute('data-e2e') || undefined,
      className: String(item.target.className || '').slice(0, 100),
      beforeTop: item.beforeTop,
      afterTop: item.afterTop,
      scrollHeight: item.target.scrollHeight,
      clientHeight: item.target.clientHeight,
    })).slice(0, 8),
  }

  function scrollTopAfter(target: HTMLElement, deltaX: number, deltaY: number): number {
    target.scrollBy({ left: deltaX, top: deltaY, behavior: 'auto' })
    return target.scrollTop
  }

  function scrollDouyinCommentTargets(
    primary: HTMLElement,
    list: HTMLElement | null,
    deltaX: number,
    deltaY: number,
    clip: { left: number; top: number; right: number; bottom: number },
  ): Array<{ target: HTMLElement; beforeTop: number; beforeLeft: number; afterTop: number; afterLeft: number; method: string }> {
    const targets = orderedCommentScrollTargets(primary, list, clip)
    const records: Array<{ target: HTMLElement; beforeTop: number; beforeLeft: number; afterTop: number; afterLeft: number; method: string }> = []
    let movedByDomScroll = false
    for (const target of targets) {
      const beforeTopValue = target.scrollTop
      const beforeLeftValue = target.scrollLeft
      target.scrollBy({ left: deltaX, top: deltaY, behavior: 'auto' })
      if (Math.abs(target.scrollTop - beforeTopValue) < 1 && Math.abs(target.scrollLeft - beforeLeftValue) < 1) {
        target.scrollTop = beforeTopValue + deltaY
        target.scrollLeft = beforeLeftValue + deltaX
      }
      records.push({
        target,
        beforeTop: beforeTopValue,
        beforeLeft: beforeLeftValue,
        afterTop: target.scrollTop,
        afterLeft: target.scrollLeft,
        method: 'dom_scroll',
      })
      if (Math.abs(target.scrollTop - beforeTopValue) > 1 || Math.abs(target.scrollLeft - beforeLeftValue) > 1) {
        movedByDomScroll = true
        break
      }
    }
    if (list && !movedByDomScroll) {
      const rect = list.getBoundingClientRect()
      const x = clamp(rect.left + rect.width * 0.62, rect.left + 16, rect.right - 24)
      const y = clamp(rect.top + rect.height * 0.42, rect.top + 32, rect.bottom - 48)
      const wheelTargets = [list, ...ancestors(list, 8)].filter(target => targets.includes(target))
      for (const target of wheelTargets.length ? wheelTargets : [list]) {
        const beforeTopValue = target.scrollTop
        const beforeLeftValue = target.scrollLeft
        dispatchWheel(target, x, y, deltaX, deltaY)
        records.push({
          target,
          beforeTop: beforeTopValue,
          beforeLeft: beforeLeftValue,
          afterTop: target.scrollTop,
          afterLeft: target.scrollLeft,
          method: 'dom_wheel_event',
        })
      }
    }
    return records
  }

  function orderedCommentScrollTargets(
    primary: HTMLElement,
    list: HTMLElement | null,
    clip: { left: number; top: number; right: number; bottom: number },
  ): HTMLElement[] {
    const out: HTMLElement[] = []
    const add = (el: HTMLElement | null | undefined) => {
      if (!el || out.includes(el)) return
      if (el === document.body || el === document.documentElement) return
      const rect = el.getBoundingClientRect()
      if (!isRegionScrollContainerCandidate(rect, clip, 'douyin.comments')) return
      out.push(el)
    }
    add(primary)
    add(list)
    for (const ancestor of ancestors(list, 10)) add(ancestor)
    return out
      .filter(el => el.scrollHeight > el.clientHeight + 4 || el.scrollWidth > el.clientWidth + 4 || el === list)
      .sort((a, b) => scoreScrollContainer(b, 'douyin.comments', clip) - scoreScrollContainer(a, 'douyin.comments', clip))
  }

  function ancestors(seed: HTMLElement | null, depth: number): HTMLElement[] {
    const out: HTMLElement[] = []
    let current = seed?.parentElement ?? null
    let remaining = depth
    while (current && remaining-- > 0) {
      out.push(current)
      current = current.parentElement
    }
    return out
  }

  function dispatchWheel(target: HTMLElement, x: number, y: number, deltaX: number, deltaY: number): void {
    target.dispatchEvent(new WheelEvent('wheel', {
      bubbles: true,
      cancelable: true,
      composed: true,
      clientX: x,
      clientY: y,
      deltaX,
      deltaY,
      deltaMode: 0,
    }))
  }

  function visibleCommentSignature(list: HTMLElement | null): string {
    if (!list) return ''
    return Array.from(list.querySelectorAll<HTMLElement>('[data-e2e="comment-item"]'))
      .map(item => {
        const author = cleanText(item.querySelector<HTMLAnchorElement>('a[href*="/user/"], a[href*="douyin.com/user"]')?.innerText || '')
        const body = cleanText(item.querySelector<HTMLElement>('.Sbe6bqNb, .LqTo7UJT, .LvAtyU_f, [data-e2e*="comment-text" i], [data-e2e*="content" i]')?.textContent || '')
        const rect = item.getBoundingClientRect()
        return `${Math.round(rect.top)}:${author}:${body}`.replace(/\s+/g, '')
      })
      .filter(Boolean)
      .slice(0, 10)
      .join('|')
      .slice(0, 700)
  }

  function findDouyinCommentListScrollRoot(
    clip: { left: number; top: number; right: number; bottom: number },
  ): HTMLElement | null {
    const list = findVisibleDouyinCommentList(clip)
    if (!list) return null
    const root = nearestScrollableAncestor(list, clip)
    return root ?? list
  }

  function findVisibleDouyinCommentList(
    clip: { left: number; top: number; right: number; bottom: number },
  ): HTMLElement | null {
    return Array.from(new Set([
      ...Array.from(document.querySelectorAll<HTMLElement>('#merge-all-comment-container [data-e2e="comment-list"]')),
      ...Array.from(document.querySelectorAll<HTMLElement>('[data-e2e="comment-list"]')),
    ]))
      .map(el => ({ el, rect: el.getBoundingClientRect() }))
      .filter(entry => isRegionScrollContainerCandidate(entry.rect, clip, 'douyin.comments'))
      .sort((a, b) => {
        const itemDiff = b.el.querySelectorAll('[data-e2e="comment-item"]').length - a.el.querySelectorAll('[data-e2e="comment-item"]').length
        if (itemDiff !== 0) return itemDiff
        return overlapAreaWithClip(b.rect, clip) - overlapAreaWithClip(a.rect, clip)
      })[0]?.el ?? null
  }

  function nearestScrollableAncestor(
    seed: HTMLElement,
    clip: { left: number; top: number; right: number; bottom: number },
  ): HTMLElement | null {
    const candidates: HTMLElement[] = []
    let current: HTMLElement | null = seed
    let depth = 0
    while (current && current !== document.body && depth++ < 10) {
      candidates.push(current)
      current = current.parentElement
    }
    return candidates
      .filter(el => el.scrollHeight > el.clientHeight + 8 || el.scrollWidth > el.clientWidth + 8)
      .map(el => ({ el, rect: el.getBoundingClientRect(), score: scoreScrollContainer(el, 'douyin.comments', clip) }))
      .filter(candidate => candidate.score > 0)
      .sort((a, b) => b.score - a.score)[0]?.el ?? null
  }

  function findScrollContainerFromRegionPoints(
    clip: { left: number; top: number; right: number; bottom: number },
  ): HTMLElement | null {
    if (typeof document.elementsFromPoint !== 'function') {
      return findScrollContainer('douyin.comments', clip)
    }
    const points = [
      [clip.left + (clip.right - clip.left) * 0.70, clip.top + (clip.bottom - clip.top) * 0.62],
      [clip.left + (clip.right - clip.left) * 0.50, clip.top + (clip.bottom - clip.top) * 0.50],
      [clip.left + (clip.right - clip.left) * 0.35, clip.top + (clip.bottom - clip.top) * 0.35],
      [clip.left + (clip.right - clip.left) * 0.50, clip.bottom - 48],
    ]
    const candidates: HTMLElement[] = []
    const seen = new Set<HTMLElement>()
    const add = (el: Element | null | undefined) => {
      if (!(el instanceof HTMLElement) || seen.has(el)) return
      seen.add(el)
      candidates.push(el)
    }
    for (const [x, y] of points) {
      for (const hit of document.elementsFromPoint(x, y).slice(0, 8)) {
        let current: Element | null = hit
        let depth = 0
        while (current instanceof HTMLElement && depth++ < 8) {
          add(current)
          current = current.parentElement
        }
      }
    }
    return candidates
      .filter(el => el.scrollHeight > el.clientHeight + 8 || el.scrollWidth > el.clientWidth + 8)
      .map(el => ({ el, rect: el.getBoundingClientRect(), score: scoreScrollContainer(el, 'douyin.comments', clip) }))
      .filter(candidate => candidate.score > 0)
      .sort((a, b) => b.score - a.score)[0]?.el ?? findScrollContainer('douyin.comments', clip)
  }

  function findScrollContainer(
    key: string,
    clip: { left: number; top: number; right: number; bottom: number },
  ): HTMLElement | null {
    const all = scrollContainerSearchElements(key, clip)
    const candidates = all
      .filter(el => {
        const rect = el.getBoundingClientRect()
        if (!isRegionScrollContainerCandidate(rect, clip, key)) return false
        if (el.scrollHeight <= el.clientHeight + 8 && el.scrollWidth <= el.clientWidth + 8) return false
        const style = getComputedStyle(el)
        const overflow = `${style.overflow} ${style.overflowY} ${style.overflowX}`.toLowerCase()
        return overflow.includes('auto') || overflow.includes('scroll') || key === 'douyin.comments'
      })
      .map(el => ({ el, rect: el.getBoundingClientRect(), score: scoreScrollContainer(el, key, clip) }))
      .filter(candidate => candidate.score > 0)
      .sort((a, b) => b.score - a.score)
    return candidates[0]?.el ?? null
  }

  function scrollContainerSearchElements(
    key: string,
    clip: { left: number; top: number; right: number; bottom: number },
  ): HTMLElement[] {
    const out: HTMLElement[] = []
    const seen = new Set<HTMLElement>()
    const add = (el: Element | null | undefined) => {
      if (!(el instanceof HTMLElement) || seen.has(el)) return
      seen.add(el)
      out.push(el)
    }
    const addWithAncestors = (el: Element | null | undefined, depth = 8) => {
      let current = el
      let remaining = depth
      while (current instanceof HTMLElement && remaining-- > 0) {
        add(current)
        current = current.parentElement
      }
    }
    const samplePoints = [
      [clip.left + (clip.right - clip.left) * 0.50, clip.top + (clip.bottom - clip.top) * 0.50],
      [clip.left + (clip.right - clip.left) * 0.72, clip.top + (clip.bottom - clip.top) * 0.62],
      [clip.left + (clip.right - clip.left) * 0.35, clip.top + (clip.bottom - clip.top) * 0.35],
      [clip.left + (clip.right - clip.left) * 0.50, clip.bottom - 42],
    ]
    if (typeof document.elementsFromPoint === 'function') {
      for (const [x, y] of samplePoints) {
        for (const el of document.elementsFromPoint(x, y)) {
          addWithAncestors(el)
        }
      }
    }
    if (key === 'douyin.comments') {
      const selector = [
        '[data-e2e*="comment" i]',
        '[data-e2e*="reply" i]',
        '[class*="comment" i]',
        '[class*="reply" i]',
        '[aria-label*="评论" i]',
        '[aria-label*="回复" i]',
        'article',
        'li',
      ].join(',')
      for (const el of Array.from(document.querySelectorAll<HTMLElement>(selector)).slice(0, 240)) {
        const rect = el.getBoundingClientRect()
        if (rect.bottom >= clip.top - 160 && rect.top <= clip.bottom + 160 && rect.right >= clip.left - 120 && rect.left <= clip.right + 120) {
          addWithAncestors(el)
        }
      }
      for (const el of looseCommentTextElements(clip).slice(0, 120)) {
        addWithAncestors(el, 10)
      }
    } else {
      for (const el of Array.from(document.querySelectorAll<HTMLElement>('[role], article, li, main, section')).slice(0, 240)) {
        addWithAncestors(el, 4)
      }
    }
    add(document.scrollingElement as Element | null)
    add(document.documentElement)
    add(document.body)
    return out
  }

  function looseCommentTextElements(
    clip: { left: number; top: number; right: number; bottom: number },
  ): HTMLElement[] {
    const controls = /^(评论|全部评论|详情|TA的作品|问AI|回复|展开|展开回复|点赞|分享|收藏|说点什么|发表评论)$/u
    return Array.from(document.querySelectorAll<HTMLElement>('span, p, div, a'))
      .map(el => ({ el, rect: el.getBoundingClientRect(), text: cleanText(el.innerText || el.textContent || '') }))
      .filter(entry => entry.text.length >= 2 && entry.text.length <= 600)
      .filter(entry => !controls.test(entry.text.replace(/\s+/g, '')))
      .filter(entry => {
        const rect = entry.rect
        if (rect.width <= 0 || rect.height <= 0) return false
        const centerX = rect.left + rect.width / 2
        const centerY = rect.top + rect.height / 2
        return centerX >= clip.left - 40 && centerX <= clip.right + 40
          && centerY >= clip.top - 80 && centerY <= clip.bottom + 80
      })
      .filter(entry => /[？?。！!，,]/u.test(entry.text) || entry.text.length > 8)
      .map(entry => entry.el)
  }

  function scoreScrollContainer(
    el: HTMLElement,
    key: string,
    clip: { left: number; top: number; right: number; bottom: number },
  ): number {
    const rect = el.getBoundingClientRect()
    const text = cleanText(el.innerText || el.textContent || '')
    const marker = `${el.getAttribute('data-e2e') || ''} ${el.className || ''} ${el.getAttribute('aria-label') || ''}`.toLowerCase()
    let score = 0
    if (key === 'douyin.comments') {
      if (marker.includes('comment')) score += 80
      if (text.includes('全部评论') || text.includes('说点什么') || text.includes('回复')) score += 70
      if (text.includes('点赞') && text.includes('分享') && !text.includes('全部评论')) score -= 80
    }
    score += Math.min(80, Math.round((el.scrollHeight - el.clientHeight) / 20))
    score += Math.round(overlapAreaWithClip(rect, clip) / 1000)
    if (rect.width > (clip.right - clip.left) * 1.35) score -= key === 'douyin.comments' ? 18 : 80
    if (rect.left < clip.left - 32) score -= key === 'douyin.comments' ? 12 : 50
    return score
  }

  function isRegionScrollContainerCandidate(
    rect: DOMRect,
    clip: { left: number; top: number; right: number; bottom: number },
    key: string,
  ): boolean {
    if (rect.width <= 0 || rect.height <= 0) return false
    const centerX = rect.left + rect.width / 2
    const centerY = rect.top + rect.height / 2
    const centerInside = centerX >= clip.left && centerX <= clip.right && centerY >= clip.top && centerY <= clip.bottom
    const overlap = overlapAreaWithClip(rect, clip)
    const elementArea = Math.max(1, rect.width * rect.height)
    if (centerInside || overlap / elementArea >= 0.5) return true
    if (key !== 'douyin.comments') return false
    const clipArea = Math.max(1, (clip.right - clip.left) * (clip.bottom - clip.top))
    const containsClip =
      rect.left <= clip.left + 24
      && rect.right >= clip.right - 24
      && rect.top <= clip.top + 80
      && rect.bottom >= clip.bottom - 80
    return containsClip || overlap / clipArea >= 0.45
  }

  function overlapAreaWithClip(
    rect: DOMRect,
    clip: { left: number; top: number; right: number; bottom: number },
  ): number {
    const left = Math.max(rect.left, clip.left)
    const right = Math.min(rect.right, clip.right)
    const top = Math.max(rect.top, clip.top)
    const bottom = Math.min(rect.bottom, clip.bottom)
    return Math.max(0, right - left) * Math.max(0, bottom - top)
  }

  function cleanText(text: string): string {
    return text.replace(/\s+/g, ' ').trim()
  }
}
