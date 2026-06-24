import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { ClickProfileActionParams } from '../types'
import { activateTabForRender } from './activate-tab'
import { ensureVisibilityOverride } from './visibility-keepalive'
import { installNetObserver, netSignalSince } from '../net_observer'

/** 关注动作标签(用于判定本次 click_profile_action 是否为"关注",决定是否接入接口确认)。 */
const FOLLOW_LABELS = ['关注', '回关', 'follow']

export interface ClickProfileActionHandlerDeps {
  chrome?: typeof globalThis.chrome
}

/** Throw CANCELLED at an await boundary if the run was aborted (action.cancel). */
function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw new ActionFailureError('CANCELLED', 'click_profile_action aborted before injection', false)
  }
}

export const clickProfileActionHandler = (
  deps: ClickProfileActionHandlerDeps = {},
): ActionHandler<ClickProfileActionParams> => {
  return async (tabId, params, deadlineMs, signal) => {
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
    const isFollow = labels.some(l => FOLLOW_LABELS.includes(l.toLowerCase()))
    // 后台保活(契约):幂等覆盖该 tab 的可见性 API、吞掉 visibilitychange,让后台标签下抖音 React
    // 不因 hidden 暂停作者主页关注/回关/私信按钮的异步渲染(根因之一:后台主页按钮没 mount,
    // 单次注入扑空 → no_profile_action)。与 douyin_search / douyin_open_video / douyin_ui 开头一致,
    // best-effort、不阻断后续。在第一次后台尝试之前注入,使首轮就有机会命中、少触发激活兜底。
    await ensureVisibilityOverride(chromeApi, tabId)

    // 关注接口确认(基于接口判定成功):点击【之前】先装网络观察器登记 commit/follow 接口。点击后
    // 据该接口回包 status_code==0 直接确认关注成功 —— 比后端轮询 DOM"已关注"文案(慢环境约 11s)
    // 又快又稳:后台 tab 的 a11y/DOM 恒空也照样能从网络回包判定。best-effort,装不上则回执无
    // followConfirmed、后端原样退回 DOM 轮询(零回归)。
    let followSinceTs = 0
    if (isFollow) {
      await installNetObserver(chromeApi, tabId, [
        { key: 'follow', urlSource: 'aweme/v1/web/commit/follow', captureBody: true },
      ])
      followSinceTs = Date.now()
    }

    const runOnce = async () => {
      const results = await chromeApi.scripting.executeScript({
        target: { tabId, allFrames: false },
        func: clickProfileActionInPage,
        args: [labels],
      })
      return results?.[0]?.result as
        | { ok?: boolean; label?: string; reason?: string }
        | undefined
    }

    // 取消优先:点关注/回关是不可逆副作用,每次注入前先看 signal,已取消则抛 CANCELLED 不注入。
    throwIfAborted(signal)
    // 1. 后台先试 —— 快机/前台一次就命中,保持静默(不激活)。
    let payload = await runOnce()
    // 2. 慢机自适应兜底:后台 tab 被 Chrome 节流时,关注/私信按钮迟迟不渲染、单次扑空。
    //    激活 tab 解除节流、让抖音全速渲染,再轮询重试几次(每次 1.5s,约 6s 渲染余量)。
    if (payload?.ok !== true) {
      throwIfAborted(signal)
      await activateTabForRender(chromeApi, tabId)
      for (let i = 0; i < 4 && payload?.ok !== true; i++) {
        await new Promise(resolve => setTimeout(resolve, 1500))
        throwIfAborted(signal)
        payload = await runOnce()
      }
    }
    if (payload?.ok !== true) {
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        payload?.reason || `profile action not found: ${labels.join('/')}`,
        false,
      )
    }
    // 关注:在剩余预算内轮询 commit/follow 回包(status_code==0)确认。已关注短路(already_followed)
    // 本就处于关注态,直接视为确认,不必等接口。命中→回执 followConfirmed=true,后端跳过 DOM 轮询。
    let followConfirmed: boolean | undefined
    if (isFollow) {
      followConfirmed = payload.label === 'already_followed'
        ? true
        : await waitFollowNetConfirm(chromeApi, tabId, followSinceTs, signal, deadlineMs)
    }
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        label: payload.label || labels[0],
        ...(isFollow ? { followConfirmed: followConfirmed === true } : {}),
      },
    }
  }
}

/**
 * 在有限预算内轮询"关注接口(commit/follow)是否回包且 status_code==0"。命中即返回 true。
 * 关注接口通常 <1s 回包,预算封顶 6s(且不超过下发 deadline 的余量);超时返回 false,
 * 由后端原样退回 DOM"已关注"轮询(零回归)。netSignalSince 读的是 MAIN world 观察器写在
 * documentElement 上的 data-mc-net-follow 属性,ISOLATED 注入即可读,无需再进 MAIN world。
 */
async function waitFollowNetConfirm(
  chromeApi: typeof globalThis.chrome,
  tabId: number,
  sinceTs: number,
  signal: AbortSignal | undefined,
  deadlineMs: number,
): Promise<boolean> {
  const budget = deadlineMs && deadlineMs > 0 ? Math.min(6000, Math.max(1500, deadlineMs - 800)) : 6000
  const until = Date.now() + budget
  for (;;) {
    if (signal?.aborted) return false
    if (await netSignalSince(chromeApi, tabId, 'follow', sinceTs, { statusCode: 0, windowMs: 20_000 })) return true
    if (Date.now() >= until) return false
    await new Promise(resolve => setTimeout(resolve, 250))
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
