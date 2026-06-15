import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { OpenAuthorFromCommentParams } from '../types'
import type { TabGroupManager } from '../../tab-group-manager'

export interface OpenAuthorFromCommentHandlerDeps {
  chrome?: typeof globalThis.chrome
  tabGroupManager?: Pick<TabGroupManager, 'addTab' | 'joinChromeGroup'>
  subject?: string
}

export const openAuthorFromCommentHandler = (
  deps: OpenAuthorFromCommentHandlerDeps = {},
): ActionHandler<OpenAuthorFromCommentParams> => {
  return async (tabId, params, _deadlineMs) => {
    if (!params || typeof params.commentText !== 'string' || params.commentText.trim().length === 0) {
      throw new ActionFailureError('HANDLER_ERROR', 'open_author_from_comment params were malformed', false)
    }
    const chromeApi = deps.chrome ?? globalThis.chrome
    if (!chromeApi?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting.executeScript is unavailable', true)
    }

    const directHref = normalizeProfileHref(params.authorProfileUrl ?? '')
    let payload: { ok?: boolean; href?: string; author?: string; reason?: string } | undefined
    if (directHref) {
      payload = { ok: true, href: directHref, author: params.authorName ?? '' }
    } else {
      const results = await chromeApi.scripting.executeScript({
        target: { tabId, allFrames: false },
        func: openAuthorFromCommentInPage,
        args: [params.commentText, params.authorName ?? ''],
      })
      payload = results?.[0]?.result as
        | { ok?: boolean; href?: string; author?: string; reason?: string }
        | undefined
    }
    if (payload?.ok !== true || !payload.href) {
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        payload?.reason || 'author profile link not found for matched comment',
        false,
      )
    }
    if (!chromeApi.tabs?.create) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.tabs.create is unavailable', true)
    }
    const createProps: chrome.tabs.CreateProperties = {
      url: payload.href,
      active: true,
      openerTabId: tabId,
    }
    let created: chrome.tabs.Tab
    try {
      created = await chromeApi.tabs.create(createProps)
    } catch (error) {
      if (!String((error as Error)?.message || error).includes('opener')) {
        throw error
      }
      const fallbackProps = { ...createProps }
      delete fallbackProps.openerTabId
      created = await chromeApi.tabs.create(fallbackProps)
    }
    if (typeof created?.id === 'number' && deps.tabGroupManager && deps.subject) {
      await deps.tabGroupManager.addTab(deps.subject, created.id)
      await deps.tabGroupManager.joinChromeGroup(deps.subject, created.id)
    }
    const readyTab = typeof created?.id === 'number'
      ? await waitForCreatedProfileTabReady(chromeApi, created.id, payload.href, _deadlineMs)
      : undefined
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        href: payload.href,
        author: payload.author || params.authorName || '',
        tabId: created?.id ?? null,
        readyUrl: readyTab?.url ?? created?.url ?? '',
        readyStatus: readyTab?.status ?? created?.status ?? '',
      },
    }
  }
}

async function waitForCreatedProfileTabReady(
  chromeApi: typeof globalThis.chrome,
  tabId: number,
  expectedHref: string,
  deadlineMs?: number,
): Promise<chrome.tabs.Tab | undefined> {
  if (!chromeApi.tabs?.get) {
    return undefined
  }
  const startedAt = Date.now()
  const budget = Math.max(1000, Math.min(8000, (deadlineMs ?? 6000) - 250))
  let last: chrome.tabs.Tab | undefined
  while (Date.now() - startedAt < budget) {
    try {
      last = await chromeApi.tabs.get(tabId)
      const url = String(last?.url || last?.pendingUrl || '')
      if (last?.status === 'complete' && profileUrlMatches(url, expectedHref)
        && await canInjectProfileReadyProbe(chromeApi, tabId, expectedHref)) {
        return last
      }
      if (last?.status === 'complete' && looksLikeProfileHref(url)
        && await canInjectProfileReadyProbe(chromeApi, tabId, expectedHref)) {
        return last
      }
    } catch {
      return last
    }
    await sleep(250)
  }
  return last
}

async function canInjectProfileReadyProbe(
  chromeApi: typeof globalThis.chrome,
  tabId: number,
  expectedHref: string,
): Promise<boolean> {
  if (!chromeApi.scripting?.executeScript) {
    return false
  }
  try {
    const result = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: profileReadyProbeInPage,
      args: [expectedHref],
    })
    return result?.[0]?.result === true
  } catch {
    return false
  }
}

function profileReadyProbeInPage(expectedHref: string): boolean {
  const href = location.href
  const ready = document.readyState === 'interactive' || document.readyState === 'complete'
  const hasBody = !!document.body
  return ready && hasBody && (profileUrlMatchesInPage(href, expectedHref) || href.includes('/user/'))

  function profileUrlMatchesInPage(actual: string, expected: string): boolean {
    const actualToken = profileTokenInPage(actual)
    const expectedToken = profileTokenInPage(expected)
    return !!actualToken && !!expectedToken && actualToken === expectedToken
  }

  function profileTokenInPage(value: string): string {
    try {
      const url = new URL(value, 'https://www.douyin.com')
      const parts = url.pathname.split('/').filter(Boolean)
      const userIndex = parts.findIndex(part => part === 'user')
      if (userIndex >= 0 && parts[userIndex + 1]) {
        return decodeURIComponent(parts[userIndex + 1]).toLowerCase()
      }
      return ''
    } catch {
      return ''
    }
  }
}

function profileUrlMatches(actual: string, expected: string): boolean {
  const actualToken = profileToken(actual)
  const expectedToken = profileToken(expected)
  return !!actualToken && !!expectedToken && actualToken === expectedToken
}

function profileToken(href: string): string {
  try {
    const url = new URL(href, 'https://www.douyin.com')
    const parts = url.pathname.split('/').filter(Boolean)
    const userIndex = parts.findIndex(part => part === 'user')
    if (userIndex >= 0 && parts[userIndex + 1]) {
      return decodeURIComponent(parts[userIndex + 1]).toLowerCase()
    }
    return ''
  } catch {
    return ''
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function normalizeProfileHref(value: string): string {
  const raw = String(value || '').trim()
  if (!raw) return ''
  try {
    const href = new URL(raw, 'https://www.douyin.com').href
    return looksLikeProfileHref(href) ? href : ''
  } catch {
    return ''
  }
}

function looksLikeProfileHref(href: string): boolean {
  const lower = String(href || '').toLowerCase()
  return lower.includes('douyin.com') && lower.includes('/user')
}

function openAuthorFromCommentInPage(
  commentText: string,
  authorName: string,
): { ok: boolean; href?: string; author?: string; reason?: string } {
  const target = normalize(commentText).replace(/[。.!！]+$/u, '')
  const authorHint = normalize(authorName)
  if (!target) return { ok: false, reason: 'empty_comment_text' }

  const candidates = Array.from(document.querySelectorAll<HTMLElement>('div, article, li, section, p, span'))
    .map((el, index) => ({
      el,
      index,
      text: normalize(el.innerText || el.textContent || ''),
      rect: el.getBoundingClientRect(),
    }))
    .filter(item => item.text.includes(target) || item.text.replace(/[。.!！]/gu, '').includes(target))
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .sort((a, b) => {
      const area = a.rect.width * a.rect.height - b.rect.width * b.rect.height
      if (Math.abs(area) > 1) return area
      return a.index - b.index
    })

  for (const item of candidates.slice(0, 20)) {
    const container = nearestContainerWithUserLink(item.el, target)
    if (!container) continue
    const links = Array.from(container.querySelectorAll<HTMLAnchorElement>('a[href]'))
      .map(a => ({
        a,
        href: absoluteHref(a.getAttribute('href') || a.href),
        text: normalize(a.innerText || a.textContent || ''),
      }))
      .filter(link => looksLikeProfileHref(link.href))
    const preferred = links.find(link => authorHint && link.text.includes(authorHint)) ?? links[0]
    if (!preferred) continue
    return { ok: true, href: preferred.href, author: preferred.text || authorHint }
  }

  return { ok: false, reason: `no_profile_link_for_comment:${target.slice(0, 40)}` }

  function nearestContainerWithUserLink(seed: HTMLElement, needle: string): HTMLElement | null {
    let current: HTMLElement | null = seed
    let depth = 0
    while (current && depth++ < 10) {
      const text = normalize(current.innerText || current.textContent || '')
      const hasTarget = text.includes(needle) || text.replace(/[。.!！]/gu, '').includes(needle)
      const hasProfile = Array.from(current.querySelectorAll<HTMLAnchorElement>('a[href]'))
        .some(a => looksLikeProfileHref(absoluteHref(a.getAttribute('href') || a.href)))
      if (hasTarget && hasProfile) return current
      current = current.parentElement
    }
    return null
  }

  function normalize(text: string): string {
    return String(text || '').replace(/\s+/g, ' ').trim()
  }

  function absoluteHref(href: string): string {
    try {
      return new URL(href, location.href).href
    } catch {
      return href
    }
  }

}
