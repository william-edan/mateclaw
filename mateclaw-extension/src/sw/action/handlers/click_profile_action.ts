import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ClickProfileActionParams } from '../types'

export interface ClickProfileActionHandlerDeps {
  chrome?: typeof globalThis.chrome
}

export const clickProfileActionHandler = (
  deps: ClickProfileActionHandlerDeps = {},
): ActionHandler<ClickProfileActionParams> => {
  return async (tabId, params, _deadlineMs) => {
    const labels = Array.isArray(params?.labels)
      ? params.labels.map(label => String(label || '').trim()).filter(Boolean)
      : []
    if (labels.length === 0) {
      throw new ActionFailureError('HANDLER_ERROR', 'click_profile_action labels are required', false)
    }
    const chromeApi = deps.chrome ?? globalThis.chrome
    if (!chromeApi?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting.executeScript is unavailable', true)
    }

    const results = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: clickProfileActionInPage,
      args: [labels],
    })
    const payload = results?.[0]?.result as
      | { ok?: boolean; label?: string; reason?: string }
      | undefined
    if (payload?.ok !== true) {
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        payload?.reason || `profile action not found: ${labels.join('/')}`,
        false,
      )
    }
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        label: payload.label || labels[0],
      },
    }
  }
}

function clickProfileActionInPage(labels: string[]): { ok: boolean; label?: string; reason?: string } {
  const wanted = labels.map(normalize).filter(Boolean)
  if (wanted.length === 0) return { ok: false, reason: 'empty_labels' }
  // 已关注短路：作者已关注时关注按钮显示“已关注/互相关注/相互关注”，按“关注”精确匹配会找不到、
  // 误报 FOLLOW_BUTTON_NOT_FOUND 并中断后续私信。这里识别已关注状态、视为成功(不再点击)，让上层
  // 继续执行私信。仅对 follow 动作生效。
  const isFollowAction = wanted.some(w => ['关注', '回关', 'follow'].includes(w.toLowerCase()))
  if (isFollowAction) {
    const followedRoot = document.querySelector('#user_detail_element,[data-e2e="user-detail"]') || document.body
    const alreadyFollowed = !!followedRoot && Array.from(
      followedRoot.querySelectorAll('button,[role="button"],div[tabindex],span[tabindex]'),
    ).some(el => ['已关注', '互相关注', '相互关注', '已互关'].includes(
      String((el as HTMLElement).innerText || el.textContent || '').replace(/\s+/g, '').trim(),
    ))
    if (alreadyFollowed) {
      console.info('[mateclaw][click_profile_action]', { ok: true, labels, strategy: 'already_followed' })
      return { ok: true, label: 'already_followed' }
    }
  }
  const viewportW = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 1)
  const viewportH = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1)
  const minContentX = Math.max(180, viewportW * 0.16)
  const maxY = Math.max(360, viewportH * 0.72)
  const selectors = [
    'button',
    '[role="button"]',
    'a',
    'div[tabindex]',
    'span[tabindex]',
  ].join(',')
  const profileRoots = Array.from(document.querySelectorAll<HTMLElement>(
    '#user_detail_element,[data-e2e="user-detail"]',
  ))
  const scopedRoots: ParentNode[] = profileRoots.length > 0 ? profileRoots : [document.body].filter(Boolean)
  const scopedCandidates = scopedRoots.flatMap(root => collectCandidates(root, selectors, 'profile_dom'))
  const documentCandidates = profileRoots.length > 0
    ? []
    : collectCandidates(document, selectors, 'document_dom')
  const candidates = [...scopedCandidates, ...documentCandidates]
    .filter(item => profileActionTextMatches(item.text, wanted))
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .filter(item => item.rect.left >= minContentX)
    .filter(item => item.rect.top >= 70 && item.rect.top <= maxY)
    .filter(item => !isDisabled(item.el))
    .sort((a, b) => score(b) - score(a) || a.index - b.index)

  const best = candidates[0]
  if (!best) {
    console.info('[mateclaw][click_profile_action]', {
      ok: false,
      labels,
      wanted,
      profileRoots: profileRoots.length,
      reason: `no_profile_action:${wanted.join('/')}`,
    })
    const diag = {
      rs: document.readyState,
      vis: document.visibilityState,
      roots: profileRoots.length,
      btns: document.querySelectorAll('button,[role="button"]').length,
      texts: Array.from(document.querySelectorAll('button,[role="button"]'))
        .map(e => ((e as HTMLElement).innerText || e.textContent || '').replace(/\s/g, '').slice(0, 10))
        .filter(Boolean).slice(0, 14),
    }
    return { ok: false, reason: `no_profile_action:${wanted.join('/')}|diag=${JSON.stringify(diag)}` }
  }
  console.info('[mateclaw][click_profile_action]', {
    ok: true,
    labels,
    matched: best.text,
    strategy: best.strategy,
    tag: best.el.tagName,
    dataE2e: best.el.getAttribute('data-e2e') || best.el.closest('[data-e2e]')?.getAttribute('data-e2e') || '',
    rect: rectSummary(best.rect),
  })
  best.el.scrollIntoView({ block: 'center', inline: 'center' })
  // 完整指针序列 + native click。部分抖音控件对裸 el.click() 不响应，需要
  // pointerdown→mousedown→pointerup→mouseup→click 的完整合成序列才认。全程页内 DOM 派发、
  // 不经 CDP Input，后台(hidden)tab 也能执行——这是后台静默触达(出路③)的关键。
  const clickOpts = { bubbles: true, cancelable: true, composed: true }
  try {
    if (typeof PointerEvent === 'function') best.el.dispatchEvent(new PointerEvent('pointerdown', clickOpts))
    best.el.dispatchEvent(new MouseEvent('mousedown', clickOpts))
    if (typeof PointerEvent === 'function') best.el.dispatchEvent(new PointerEvent('pointerup', clickOpts))
    best.el.dispatchEvent(new MouseEvent('mouseup', clickOpts))
  } catch { /* best-effort */ }
  best.el.click()
  return { ok: true, label: best.text }

  function collectCandidates(
    root: ParentNode,
    selectorText: string,
    strategy: 'profile_dom' | 'document_dom',
  ): Array<{ el: HTMLElement; index: number; rect: DOMRect; text: string; strategy: string }> {
    return Array.from(root.querySelectorAll<HTMLElement>(selectorText))
      .map((el, index) => {
        const rect = el.getBoundingClientRect()
        const text = normalize(el.innerText || el.textContent || el.getAttribute('aria-label') || el.title || '')
        return { el, index, rect, text, strategy }
      })
  }

  function score(item: { rect: DOMRect; text: string; el: HTMLElement; strategy: string }): number {
    let value = 0
    const role = (item.el.getAttribute('role') || item.el.tagName || '').toLowerCase()
    if (item.strategy === 'profile_dom') value += 100
    if (role.includes('button')) value += 100
    if (wanted.some(label => normalize(item.text) === label)) value += 80
    if (item.el.closest('#user_detail_element,[data-e2e="user-detail"]')) value += 80
    if (item.el.matches('button,[role="button"]')) value += 60
    if (item.el.getAttribute('data-e2e') === 'user-info-follow-btn') value += 50
    if (item.rect.width >= 42 && item.rect.width <= 180 && item.rect.height >= 22 && item.rect.height <= 64) {
      value += 50
    }
    if (item.text.includes('已关注') || item.text.includes('互相关注')) value += 40
    if (item.text.includes('分享主页') || item.el.id === 'frame-user-info-share-button') value -= 120
    if (isUnsafeProfileActionText(item.text)) value -= 1_000
    return value
  }

  function profileActionTextMatches(text: string, actionLabels: string[]): boolean {
    const normalized = normalize(text)
    if (!normalized || isUnsafeProfileActionText(normalized)) return false
    return actionLabels.some(label => normalized === label || normalized.toLowerCase() === label.toLowerCase())
  }

  function isUnsafeProfileActionText(text: string): boolean {
    const normalized = normalize(text).toLowerCase()
    return normalized.includes('下载')
      || normalized.includes('客户端')
      || normalized.includes('桌面快捷')
      || normalized.includes('download')
  }

  function normalize(text: string): string {
    return String(text || '').replace(/\s+/g, '').trim()
  }

  function isDisabled(el: HTMLElement): boolean {
    return el.getAttribute('aria-disabled') === 'true'
      || el.getAttribute('disabled') === 'true'
      || (el instanceof HTMLButtonElement && el.disabled)
  }

  function rectSummary(rect: DOMRect): { x: number; y: number; width: number; height: number } {
    return {
      x: Math.round(rect.left),
      y: Math.round(rect.top),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
    }
  }
}
