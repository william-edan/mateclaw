import { afterEach, describe, expect, it, vi } from 'vitest'
import { clickProfileActionHandler } from './click_profile_action'

function chromeWithDomExecution() {
  return {
    scripting: {
      executeScript: vi.fn(async ({ func, args }) => [{
        result: func(...args),
      }]),
    },
  } as unknown as typeof chrome
}

function setRect(el: Element, rect: Partial<DOMRect>) {
  const full = {
    left: rect.left ?? rect.x ?? 0,
    top: rect.top ?? rect.y ?? 0,
    width: rect.width ?? 80,
    height: rect.height ?? 36,
    right: (rect.left ?? rect.x ?? 0) + (rect.width ?? 80),
    bottom: (rect.top ?? rect.y ?? 0) + (rect.height ?? 36),
    x: rect.x ?? rect.left ?? 0,
    y: rect.y ?? rect.top ?? 0,
    toJSON: () => ({}),
  } as DOMRect
  vi.spyOn(el, 'getBoundingClientRect').mockReturnValue(full)
}

describe('click profile action handler', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('clicks the private message button from Douyin profile DOM before generic page controls', async () => {
    document.body.innerHTML = `
      <button id="outside-share">分享主页</button>
      <div data-e2e="user-detail" id="user_detail_element">
        <button id="more" type="button"><span>...</span></button>
        <button id="share" type="button"><span>分享主页</span></button>
        <div class="actions">
          <button id="dm" type="button" aria-disabled="false"><span>私信</span></button>
          <button id="follow" data-e2e="user-info-follow-btn" type="button"><span>关注</span></button>
        </div>
      </div>
    `
    Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true })
    setRect(document.querySelector('#outside-share')!, { left: 900, top: 90, width: 120, height: 36 })
    setRect(document.querySelector('#more')!, { left: 760, top: 172, width: 48, height: 36 })
    setRect(document.querySelector('#share')!, { left: 816, top: 172, width: 120, height: 36 })
    setRect(document.querySelector('#dm')!, { left: 816, top: 224, width: 86, height: 36 })
    setRect(document.querySelector('#follow')!, { left: 912, top: 224, width: 86, height: 36 })
    const dmClick = vi.fn()
    document.querySelector('#dm')!.addEventListener('click', dmClick)
    vi.spyOn(console, 'info').mockImplementation(() => {})
    const handler = clickProfileActionHandler({ chrome: chromeWithDomExecution() })

    const result = await handler(42, { labels: ['私信', '发私信'] }, 5000)

    expect(result.ok).toBe(true)
    expect(result.payload).toEqual({ label: '私信' })
    expect(dmClick).toHaveBeenCalledTimes(1)
  })

  it('clicks the real follow button inside user detail rather than profile counters', async () => {
    document.body.innerHTML = `
      <div data-e2e="user-detail" id="user_detail_element">
        <div data-e2e="user-info-follow" tabindex="0">
          <div>关注</div><div>339</div>
        </div>
        <button id="follow" data-e2e="user-info-follow-btn" type="button"><span>关注</span></button>
      </div>
    `
    Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true })
    setRect(document.querySelector('[data-e2e="user-info-follow"]')!, { left: 520, top: 150, width: 90, height: 48 })
    setRect(document.querySelector('#follow')!, { left: 912, top: 224, width: 86, height: 36 })
    const counterClick = vi.fn()
    const followClick = vi.fn()
    document.querySelector('[data-e2e="user-info-follow"]')!.addEventListener('click', counterClick)
    document.querySelector('#follow')!.addEventListener('click', followClick)
    vi.spyOn(console, 'info').mockImplementation(() => {})
    const handler = clickProfileActionHandler({ chrome: chromeWithDomExecution() })

    const result = await handler(42, { labels: ['关注'] }, 5000)

    expect(result.ok).toBe(true)
    expect(result.payload).toEqual({ label: '关注' })
    expect(followClick).toHaveBeenCalledTimes(1)
    expect(counterClick).not.toHaveBeenCalled()
  })

  it('clicks the exact private message button instead of the large download CTA', async () => {
    document.body.innerHTML = `
      <div data-e2e="user-detail" id="user_detail_element">
        <button id="followed" type="button"><span>已关注</span></button>
        <button id="dm" type="button"><span>私信</span></button>
        <button id="download" type="button">
          <span>下载电脑客户端，桌面快捷访问</span>
          <span>下载</span>
        </button>
      </div>
    `
    Object.defineProperty(window, 'innerWidth', { value: 1280, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true })
    setRect(document.querySelector('#followed')!, { left: 760, top: 172, width: 130, height: 44 })
    setRect(document.querySelector('#dm')!, { left: 908, top: 172, width: 130, height: 44 })
    setRect(document.querySelector('#download')!, { left: 560, top: 236, width: 420, height: 56 })
    const dmClick = vi.fn()
    const downloadClick = vi.fn()
    document.querySelector('#dm')!.addEventListener('click', dmClick)
    document.querySelector('#download')!.addEventListener('click', downloadClick)
    vi.spyOn(console, 'info').mockImplementation(() => {})
    const handler = clickProfileActionHandler({ chrome: chromeWithDomExecution() })

    const result = await handler(42, { labels: ['私信', '发私信', 'Message', '发消息'] }, 5000)

    expect(result.ok).toBe(true)
    expect(result.payload).toEqual({ label: '私信' })
    expect(dmClick).toHaveBeenCalledTimes(1)
    expect(downloadClick).not.toHaveBeenCalled()
  })
})
