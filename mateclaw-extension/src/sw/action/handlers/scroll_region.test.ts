import { describe, expect, it, vi } from 'vitest'
import { RegionRegistry } from '../../../runtime/region-registry'
import { scrollRegionHandler } from './scroll_region'
import type { ActionResult } from '../types'

// 临时调试开关 DOM_ONLY_NO_CDP_FALLBACK 生产默认 true(只走 DOM、不回退 CDP)。
// 本测试套件覆盖的是"DOM 失败 → CDP 兜底"旧行为,故在测试中关掉该开关。见 debug-flags.ts。
vi.mock('./debug-flags', () => ({ DOM_ONLY_NO_CDP_FALLBACK: false }))

function ok(payload: Record<string, unknown> = {}): ActionResult {
  return { ok: true, elapsed_ms: 0, payload }
}

function chromeWithDomExecution() {
  return {
    scripting: {
      executeScript: vi.fn(async ({ func, args }) => [{
        result: func(...args),
      }]),
    },
  } as unknown as typeof chrome
}

/**
 * A chrome whose injected DOM scroll reports "not moved", forcing the
 * douyin.comments path to fall back to the CDP wheel. Used by the wheel-evidence
 * tests, which exercise the fallback path (the DOM container path is preferred
 * first, then falls back to the wheel when DOM didn't move).
 */
function chromeWithDomScrollFailed() {
  return {
    scripting: {
      executeScript: vi.fn(async () => [{
        result: { ok: false, reason: 'dom_scroll_disabled_for_test' },
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

describe('scroll_region handler', () => {
  it('uses the registered region center and delegates to the compatible scroll handler', async () => {
    const regions = new RegionRegistry()
    regions.register({
      key: 'feed',
      tabId: 42,
      rect: { x: 10, y: 20, width: 200, height: 300 },
    })
    const scroll = vi.fn(async () => ok({}))
    const handler = scrollRegionHandler({ regions, scroll })

    const result = await handler(42, {
      regionKey: 'feed',
      direction: 'down',
      amount: 500,
      stopWhen: { type: 'edge' },
      segments: 5,
    }, 1000)

    expect(result.ok).toBe(true)
    expect(scroll).toHaveBeenCalledExactlyOnceWith(42, {
      direction: 'down',
      distance_px: 500,
      segments: 5,
      x: 110,
      y: 170,
    }, 1000)
  })

  it('accepts snake_case region params from older edge payloads', async () => {
    const regions = new RegionRegistry()
    regions.register({
      key: 'feed',
      tabId: 42,
      rect: { x: 10, y: 20, width: 200, height: 300 },
    })
    const scroll = vi.fn(async () => ok({}))
    const handler = scrollRegionHandler({ regions, scroll })

    const result = await handler(42, {
      region_key: 'feed',
      direction: 'down',
      amount: 500,
      stop_when: { type: 'edge' },
      segments: 5,
    } as never, 1000)

    expect(result.ok).toBe(true)
    expect(scroll).toHaveBeenCalledExactlyOnceWith(42, {
      direction: 'down',
      distance_px: 500,
      segments: 5,
      x: 110,
      y: 170,
    }, 1000)
  })

  it('uses the fast comment-list DOM scroll for Douyin and skips the CDP wheel', async () => {
    document.body.innerHTML = `
      <main id="video">视频区域 点赞 分享</main>
      <aside id="comments" class="comment-panel">
        <div data-e2e="comment-list" id="list">
          <div data-e2e="comment-item" id="item1">
            <a href="https://www.douyin.com/user/ly">Ly</a>
            <div class="LvAtyU_f" id="body1">对于99%的人用豆包就行了。</div>
            <div class="w9APAHwo" id="share1">分享</div>
          </div>
        </div>
        <div>说点什么</div>
      </aside>
    `
    const comments = document.querySelector('#comments') as HTMLElement
    const list = document.querySelector('#list') as HTMLElement
    const item1 = document.querySelector('#item1') as HTMLElement
    const body1 = document.querySelector('#body1') as HTMLElement
    const share1 = document.querySelector('#share1') as HTMLElement
    mockRect(document.querySelector('#video')!, { x: 0, y: 0, width: 1200, height: 800 })
    mockRect(comments, { x: 1300, y: 0, width: 520, height: 800 })
    mockRect(list, { x: 1320, y: 90, width: 500, height: 650 })
    mockRect(item1, { x: 1320, y: 140, width: 500, height: 120 })
    mockRect(body1, { x: 1380, y: 178, width: 300, height: 28 })
    mockRect(share1, { x: 1760, y: 204, width: 48, height: 24 })
    Object.defineProperty(comments, 'clientHeight', { value: 400, configurable: true })
    Object.defineProperty(comments, 'scrollHeight', { value: 1200, configurable: true })
    Object.defineProperty(list, 'clientHeight', { value: 650, configurable: true })
    Object.defineProperty(list, 'scrollHeight', { value: 1800, configurable: true })
    let listScrollTop = 0
    Object.defineProperty(list, 'scrollTop', {
      get: () => listScrollTop,
      set: value => { listScrollTop = Number(value) },
      configurable: true,
    })
    comments.scrollBy = vi.fn()
    list.scrollBy = vi.fn(({ top }: ScrollToOptions = {}) => { listScrollTop += Number(top ?? 0) })

    const regions = new RegionRegistry()
    regions.register({
      key: 'douyin.comments',
      tabId: 42,
      rect: { x: 1280, y: 0, width: 600, height: 820 },
    })
    const scroll = vi.fn(async () => ok({}))
    const handler = scrollRegionHandler({ regions, scroll, chrome: chromeWithDomExecution() })

    const result = await handler(42, {
      regionKey: 'douyin.comments',
      direction: 'down',
      amount: 500,
    }, 1000)

    expect(result.ok).toBe(true)
    expect(result.ok && result.payload).toEqual(expect.objectContaining({
      mode: 'dom_douyin_fast',
      moved: true,
      containerE2E: 'comment-list',
    }))
    // 后台静默优先:抖音评论列表自身就是滚动容器,直接滚到底续拉,不退回 CDP 鼠标滚轮
    expect(scroll).not.toHaveBeenCalled()
  })

  it('reports no movement when CDP wheel only changes scrollTop but visible comments do not change', async () => {
    document.body.innerHTML = `
      <main id="video">视频区域 点赞 分享</main>
      <aside id="panel">
        <div id="list" data-e2e="comment-list">
          <div data-e2e="comment-item">Ly<span>对于99%的人用豆包就行了。</span></div>
          <button class="comment-reply-expand-btn">展开18条回复</button>
        </div>
      </aside>
    `
    const panel = document.querySelector('#panel') as HTMLElement
    const list = document.querySelector('#list') as HTMLElement
    mockRect(document.querySelector('#video')!, { x: 0, y: 0, width: 1200, height: 800 })
    mockRect(panel, { x: 1300, y: 0, width: 560, height: 800 })
    mockRect(list, { x: 1320, y: 90, width: 520, height: 650 })
    Object.defineProperty(list, 'clientHeight', { value: 650, configurable: true })
    Object.defineProperty(list, 'scrollHeight', { value: 1800, configurable: true })
    let listScrollTop = 0
    Object.defineProperty(list, 'scrollTop', {
      get: () => listScrollTop,
      set: value => { listScrollTop = Number(value) },
      configurable: true,
    })
    list.scrollBy = vi.fn(({ top }: ScrollToOptions) => { listScrollTop += Number(top ?? 0) })
    Object.defineProperty(panel, 'clientHeight', { value: 800, configurable: true })
    Object.defineProperty(panel, 'scrollHeight', { value: 800, configurable: true })
    panel.scrollBy = vi.fn()

    const regions = new RegionRegistry()
    regions.register({
      key: 'douyin.comments',
      tabId: 42,
      rect: { x: 1280, y: 0, width: 620, height: 820 },
    })
    const scroll = vi.fn(async () => {
      listScrollTop += 500
      return ok({})
    })
    // DOM 滚动置为失败,强制走 CDP 滚轮兜底,以测试滚轮证据逻辑
    const handler = scrollRegionHandler({ regions, scroll, chrome: chromeWithDomScrollFailed() })

    const result = await handler(42, {
      regionKey: 'douyin.comments',
      direction: 'down',
      amount: 500,
    }, 1000)

    expect(result.ok).toBe(true)
    expect(result.ok && result.payload).toEqual(expect.objectContaining({
      mode: 'comment_region_wheel',
      moved: false,
      reason: 'comment_region_wheel_not_moved',
    }))
    expect(list.scrollBy).not.toHaveBeenCalled()
    expect(panel.scrollBy).not.toHaveBeenCalled()
    expect(scroll).toHaveBeenCalled()
  })

  it('fails with a typed error when the region is missing', async () => {
    const handler = scrollRegionHandler({
      regions: new RegionRegistry(),
      scroll: vi.fn(async () => ok({})),
    })

    await expect(handler(42, {
      regionKey: 'missing',
      direction: 'down',
      amount: 500,
    }, 1000)).rejects.toMatchObject({
      code: 'GROUNDING_AMBIGUOUS',
      retryable: true,
    })
  })
})
