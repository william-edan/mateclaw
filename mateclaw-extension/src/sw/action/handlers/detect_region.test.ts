import { describe, expect, it, vi } from 'vitest'
import { RegionRegistry } from '../../../runtime/region-registry'
import { detectRegionHandler } from './detect_region'

function chromeWithDomExecution() {
  return {
    scripting: {
      executeScript: vi.fn(async ({ func, args }) => [{
        result: await func(...args),
      }]),
    },
  } as unknown as typeof chrome
}

function mockRect(el: Element, rect: Partial<DOMRect>): void {
  vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
    x: rect.x ?? rect.left ?? 0,
    y: rect.y ?? rect.top ?? 0,
    left: rect.left ?? rect.x ?? 0,
    top: rect.top ?? rect.y ?? 0,
    right: rect.right ?? ((rect.x ?? rect.left ?? 0) + (rect.width ?? 0)),
    bottom: rect.bottom ?? ((rect.y ?? rect.top ?? 0) + (rect.height ?? 0)),
    width: rect.width ?? 0,
    height: rect.height ?? 0,
    toJSON: () => ({}),
  } as DOMRect)
}

describe('detect_region handler', () => {
  it('detects and registers Douyin comments panel from DOM evidence', async () => {
    Object.defineProperty(window, 'innerWidth', { value: 1920, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 855, configurable: true })
    document.body.innerHTML = `
      <main id="page">
        <section id="video">点赞 分享 收藏</section>
        <aside id="comments" data-e2e="comment-panel">
          <h2>全部评论 151</h2>
          <div>小明</div>
          <p>对于99%的人用豆包就行了。</p>
          <textarea placeholder="说点什么"></textarea>
        </aside>
      </main>
    `
    mockRect(document.querySelector('#page')!, { x: 0, y: 0, width: 1920, height: 855 })
    mockRect(document.querySelector('#video')!, { x: 0, y: 0, width: 1320, height: 855 })
    mockRect(document.querySelector('#comments')!, { x: 1347, y: 0, width: 573, height: 855 })
    Object.defineProperty(document.querySelector('#comments')!, 'scrollHeight', { value: 1600, configurable: true })
    Object.defineProperty(document.querySelector('#comments')!, 'clientHeight', { value: 855, configurable: true })

    const regions = new RegionRegistry()
    const handler = detectRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(42, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.payload).toEqual(expect.objectContaining({
        regionKey: 'douyin.comments',
        rect: { x: 1347, y: 0, width: 573, height: 855 },
        safePoint: { x: expect.any(Number), y: expect.any(Number) },
      }))
    }
    expect(regions.get(42, 'douyin.comments')).toEqual(expect.objectContaining({
      x: 1347,
      y: 0,
      width: 573,
      height: 855,
    }))
  })

  it('prefers the concrete Douyin comment-list DOM region when available', async () => {
    Object.defineProperty(window, 'innerWidth', { value: 1920, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 855, configurable: true })
    document.body.innerHTML = `
      <main id="modal">
        <section id="video">点赞 分享 收藏</section>
        <aside id="side">
          <div id="list" data-e2e="comment-list">
            <div data-e2e="comment-item">Ly<span>对于99%的人用豆包就行了。</span></div>
            <button class="comment-reply-expand-btn">展开18条回复</button>
          </div>
          <div id="composer">说点什么</div>
        </aside>
      </main>
    `
    mockRect(document.querySelector('#modal')!, { x: 0, y: 0, width: 1920, height: 855 })
    mockRect(document.querySelector('#video')!, { x: 0, y: 0, width: 1320, height: 855 })
    mockRect(document.querySelector('#side')!, { x: 1347, y: 0, width: 573, height: 855 })
    mockRect(document.querySelector('#list')!, { x: 1360, y: 120, width: 520, height: 640 })
    Object.defineProperty(document.querySelector('#list')!, 'scrollHeight', { value: 1600, configurable: true })
    Object.defineProperty(document.querySelector('#list')!, 'clientHeight', { value: 640, configurable: true })

    const regions = new RegionRegistry()
    const handler = detectRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(42, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.payload).toEqual(expect.objectContaining({
        regionKey: 'douyin.comments',
        rect: { x: 1360, y: 120, width: 520, height: 640 },
        source: expect.stringContaining('comment-list'),
      }))
    }
  })

  it('infers Douyin comments panel from child signals when container has no comment marker', async () => {
    Object.defineProperty(window, 'innerWidth', { value: 1920, configurable: true })
    Object.defineProperty(window, 'innerHeight', { value: 855, configurable: true })
    document.body.innerHTML = `
      <main id="modal">
        <section id="video">点赞 分享 收藏</section>
        <aside id="side">
          <div id="tabs">
            <button>详情</button>
            <button>评论</button>
          </div>
          <div id="list">
            <article>
              <a href="/user/MS4wLjABAAAA">Ly</a>
              <p>对于99%的人用豆包就行了。</p>
              <button>回复</button>
            </article>
          </div>
          <div id="composer" contenteditable="true" aria-label="说点什么"></div>
        </aside>
      </main>
    `
    mockRect(document.querySelector('#modal')!, { x: 0, y: 0, width: 1920, height: 855 })
    mockRect(document.querySelector('#video')!, { x: 0, y: 0, width: 1320, height: 855 })
    mockRect(document.querySelector('#side')!, { x: 1347, y: 0, width: 573, height: 855 })
    mockRect(document.querySelector('#tabs')!, { x: 1347, y: 60, width: 573, height: 60 })
    mockRect(document.querySelectorAll('button')[1]!, { x: 1460, y: 74, width: 80, height: 36 })
    mockRect(document.querySelector('#list')!, { x: 1347, y: 120, width: 573, height: 650 })
    mockRect(document.querySelector('article')!, { x: 1370, y: 180, width: 520, height: 110 })
    mockRect(document.querySelector('a')!, { x: 1400, y: 188, width: 80, height: 24 })
    mockRect(document.querySelector('p')!, { x: 1400, y: 220, width: 310, height: 32 })
    mockRect(document.querySelector('#composer')!, { x: 1370, y: 790, width: 520, height: 42 })
    Object.defineProperty(document.querySelector('#side')!, 'scrollHeight', { value: 1200, configurable: true })
    Object.defineProperty(document.querySelector('#side')!, 'clientHeight', { value: 855, configurable: true })

    const regions = new RegionRegistry()
    const handler = detectRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(42, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.payload).toEqual(expect.objectContaining({
        regionKey: 'douyin.comments',
        rect: { x: 1347, y: 0, width: 573, height: 855 },
      }))
      expect(String(result.payload.source)).toContain('dom_detect:')
    }
  })
})
