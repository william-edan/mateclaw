import { describe, expect, it, vi } from 'vitest'
import { RegionRegistry } from '../../../runtime/region-registry'
import { extractRegionHandler } from './extract_region'

function chromeWithDomExecution() {
  return {
    scripting: {
      executeScript: vi.fn(async ({ func, args }) => [{
        result: func(...args),
      }]),
    },
  } as unknown as typeof chrome
}

function chromeWithIsolatedDomExecution() {
  return {
    scripting: {
      executeScript: vi.fn(async ({ func, args }) => {
        const isolated = (0, eval)(`(${func.toString()})`) as (...values: unknown[]) => unknown
        return [{ result: isolated(...args) }]
      }),
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

describe('extract_region handler', () => {
  it('extracts structured DOM text items clipped to a registered region', async () => {
    document.body.innerHTML = `
      <section>
        <a id="inside" role="link" href="/profile/alice">Alice profile</a>
        <p id="outside">Outside text</p>
      </section>
    `
    mockRect(document.querySelector('#inside')!, { x: 20, y: 30, width: 120, height: 20 })
    mockRect(document.querySelector('#outside')!, { x: 500, y: 30, width: 120, height: 20 })

    const regions = new RegionRegistry()
    regions.register({ key: 'panel', tabId: 7, rect: { x: 0, y: 0, width: 250, height: 250 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(7, { regionKey: 'panel' }, 1000)

    expect(result.ok).toBe(true)
    expect(result.ok && result.payload.items).toEqual([
      expect.objectContaining({
        text: 'Alice profile',
        role: 'link',
        tag: 'a',
        href: expect.stringContaining('/profile/alice'),
        bbox: { x: 20, y: 30, width: 120, height: 20 },
      }),
    ])
  })

  it('groups Douyin-style comments as best-effort comment items', async () => {
    document.body.innerHTML = `
      <button id="stop">Stop Agent</button>
      <div id="comment" data-e2e="comment-item">
        <a class="user-name" href="https://www.douyin.com/user/MS4w">小明</a>
        <span class="comment-text">这个地方很适合亲子周末游</span>
        <button>回复</button>
      </div>
    `
    mockRect(document.querySelector('#comment')!, { x: 10, y: 10, width: 300, height: 72 })
    mockRect(document.querySelector('.user-name')!, { x: 18, y: 18, width: 60, height: 20 })
    mockRect(document.querySelector('.comment-text')!, { x: 18, y: 42, width: 220, height: 20 })
    mockRect(document.querySelector('#stop')!, { x: 20, y: 120, width: 90, height: 24 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 0, y: 0, width: 400, height: 400 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    const items = result.ok ? result.payload.items as any[] : []
    expect(items).toEqual([
      expect.objectContaining({
        itemType: 'douyin_comment',
        author: expect.stringContaining('小明'),
        text: expect.stringContaining('亲子周末游'),
        hrefs: [expect.stringContaining('douyin.com/user')],
      }),
    ])
    expect(items.map(item => item.text)).not.toContain('Stop Agent')
  })

  it('extracts Douyin comment-list items without mixing expand/share controls into the body', async () => {
    document.body.innerHTML = `
      <div data-e2e="comment-list" id="list">
        <div>
          <div class="xzjbH9pV" data-e2e="comment-item" id="item">
            <a id="avatar" href="//www.douyin.com/user/MS4wAvatar"><img alt="别让我通宵头像"></a>
            <a id="author" href="//www.douyin.com/user/MS4wTitle"><span data-click-from="title">别让我通宵</span></a>
            <div data-e2e="video-comment-more">...</div>
            <div class="LvAtyU_f" id="body">
              <span>转发 · 自热不香吗</span>
              <span class="comment-item-tag-text">作者回复过</span>
            </div>
            <div class="GOkWHE6S">1月前·广东</div>
            <div class="comment-item-stats-container"><span>16</span><div>分享</div><span>回复</span></div>
          </div>
          <button class="comment-reply-expand-btn"><span>展开18条回复</span></button>
        </div>
      </div>
    `
    mockRect(document.querySelector('#list')!, { x: 1000, y: 80, width: 520, height: 700 })
    mockRect(document.querySelector('#item')!, { x: 1010, y: 120, width: 500, height: 180 })
    mockRect(document.querySelector('#avatar')!, { x: 1020, y: 130, width: 40, height: 40 })
    mockRect(document.querySelector('#author')!, { x: 1070, y: 130, width: 90, height: 24 })
    mockRect(document.querySelector('#body')!, { x: 1070, y: 166, width: 220, height: 28 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 980, y: 60, width: 560, height: 740 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    const comments = result.ok ? (result.payload.items as any[]).filter(item => item.itemType === 'douyin_comment') : []
    expect(comments).toEqual([
      expect.objectContaining({
        author: '别让我通宵',
        text: '自热不香吗',
        href: expect.stringContaining('douyin.com/user/MS4w'),
        visibleInRegion: true,
      }),
    ])
    expect(comments[0].text).not.toContain('展开18条回复')
    expect(comments[0].text).not.toContain('分享')
    expect(comments[0].text).not.toContain('作者回复过')
    expect(comments[0].text).not.toContain('转发')
  })

  it('extracts Douyin comments from direct comment-list child slots', async () => {
    document.body.innerHTML = `
      <div data-e2e="comment-list" id="list">
        <div id="slot-1">
          <div data-e2e="comment-item" id="item-1">
            <a id="author-1" href="//www.douyin.com/user/MS4wTitle"><span data-click-from="title">全先生</span></a>
            <div id="body-1"><span>别人的</span><a href="//www.douyin.com/search/%E6%98%93%E4%BC%81%E7%A7%80">易企秀</a><span>，怎么可以修改一下变成自己的？</span></div>
            <div id="time-1"><span>6年前</span></div>
            <div class="comment-item-stats-container"><span>回复</span><span>分享</span></div>
          </div>
        </div>
        <div id="slot-2">
          <a id="author-2" href="//www.douyin.com/user/MS4wOtherTitle"><span data-click-from="title">用户8659427775691</span></a>
          <div id="body-2"><span>可以用自己的模板素材制作H5吗</span></div>
        </div>
        <div id="end">暂时没有更多评论</div>
      </div>
    `
    mockRect(document.querySelector('#list')!, { x: 1000, y: 80, width: 520, height: 700 })
    mockRect(document.querySelector('#slot-1')!, { x: 1010, y: 120, width: 500, height: 120 })
    mockRect(document.querySelector('#item-1')!, { x: 1010, y: 120, width: 500, height: 110 })
    mockRect(document.querySelector('#author-1')!, { x: 1070, y: 130, width: 90, height: 24 })
    mockRect(document.querySelector('#body-1')!, { x: 1070, y: 166, width: 220, height: 28 })
    mockRect(document.querySelector('#slot-2')!, { x: 1010, y: 260, width: 500, height: 120 })
    mockRect(document.querySelector('#author-2')!, { x: 1070, y: 270, width: 150, height: 24 })
    mockRect(document.querySelector('#body-2')!, { x: 1070, y: 306, width: 260, height: 28 })
    mockRect(document.querySelector('#end')!, { x: 1130, y: 420, width: 160, height: 28 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 980, y: 60, width: 560, height: 740 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithIsolatedDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    const comments = result.ok ? (result.payload.items as any[]).filter(item => item.itemType === 'douyin_comment') : []
    const diagnostics = result.ok ? result.payload.diagnostics as any : {}
    expect(diagnostics).toEqual(expect.objectContaining({
      commentListCount: 1,
      documentCommentItems: 1,
      selectedListDirectDivs: 3,
      extractedDomCommentCount: 2,
    }))
    expect(comments).toEqual([
      expect.objectContaining({
        author: '全先生',
        text: '别人的易企秀，怎么可以修改一下变成自己的？',
      }),
      expect.objectContaining({
        author: '用户8659427775691',
        text: '可以用自己的模板素材制作H5吗',
      }),
    ])
    expect(comments.map(comment => comment.text)).not.toContain('暂时没有更多评论')
  })

  it('starts Douyin comment extraction from the requested slot index', async () => {
    document.body.innerHTML = `
      <div data-e2e="comment-list" id="list">
        <div id="slot-1">
          <a id="author-1" href="//www.douyin.com/user/one"><span data-click-from="title">用户一</span></a>
          <div id="body-1"><span>第一条旧评论</span></div>
        </div>
        <div id="slot-2">
          <a id="author-2" href="//www.douyin.com/user/two"><span data-click-from="title">用户二</span></a>
          <div id="body-2"><span>第二条新评论</span></div>
        </div>
        <div id="slot-3">
          <a id="author-3" href="//www.douyin.com/user/three"><span data-click-from="title">用户三</span></a>
          <div id="body-3"><span>第三条新评论</span></div>
        </div>
      </div>
    `
    mockRect(document.querySelector('#list')!, { x: 1000, y: 80, width: 520, height: 700 })
    for (let index = 1; index <= 3; index += 1) {
      mockRect(document.querySelector(`#slot-${index}`)!, { x: 1010, y: 120 + index * 90, width: 500, height: 80 })
      mockRect(document.querySelector(`#author-${index}`)!, { x: 1070, y: 126 + index * 90, width: 90, height: 24 })
      mockRect(document.querySelector(`#body-${index}`)!, { x: 1070, y: 160 + index * 90, width: 220, height: 28 })
    }

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 980, y: 60, width: 560, height: 740 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithIsolatedDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments', startIndex: 1 }, 1000)

    expect(result.ok).toBe(true)
    const comments = result.ok ? (result.payload.items as any[]).filter(item => item.itemType === 'douyin_comment') : []
    const diagnostics = result.ok ? result.payload.diagnostics as any : {}
    expect(diagnostics).toEqual(expect.objectContaining({
      startIndex: 1,
      skippedSlots: 1,
      slotCount: 3,
      scannedSlots: 2,
      nextStartIndex: 3,
      slotAccessMode: 'indexed_children',
      extractedDomCommentCount: 2,
    }))
    expect(comments.map(comment => comment.text)).toEqual(['第二条新评论', '第三条新评论'])
  })

  it('advances Douyin comment cursor by scanned DOM slots instead of matched comments', async () => {
    document.body.innerHTML = `
      <div data-e2e="comment-list" id="list">
        <div id="slot-1">
          <a id="author-1" href="//www.douyin.com/user/one"><span data-click-from="title">用户一</span></a>
          <div id="body-1"><span>第一条旧评论</span></div>
        </div>
        <div id="slot-2">加载中</div>
        <div id="slot-3">
          <a id="author-3" href="//www.douyin.com/user/three"><span data-click-from="title">用户三</span></a>
          <div id="body-3"><span>第三条新评论</span></div>
        </div>
        <div id="slot-4">
          <a id="author-4" href="//www.douyin.com/user/four"><span data-click-from="title">用户四</span></a>
          <div id="body-4"><span>第四条新评论</span></div>
        </div>
      </div>
    `
    mockRect(document.querySelector('#list')!, { x: 1000, y: 80, width: 520, height: 700 })
    for (const index of [1, 2, 3, 4]) {
      mockRect(document.querySelector(`#slot-${index}`)!, { x: 1010, y: 120 + index * 90, width: 500, height: 80 })
    }
    for (const index of [1, 3, 4]) {
      mockRect(document.querySelector(`#author-${index}`)!, { x: 1070, y: 126 + index * 90, width: 90, height: 24 })
      mockRect(document.querySelector(`#body-${index}`)!, { x: 1070, y: 160 + index * 90, width: 220, height: 28 })
    }

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 980, y: 60, width: 560, height: 740 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithIsolatedDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments', maxItems: 1, startIndex: 1 }, 1000)

    expect(result.ok).toBe(true)
    const comments = result.ok ? (result.payload.items as any[]).filter(item => item.itemType === 'douyin_comment') : []
    const diagnostics = result.ok ? result.payload.diagnostics as any : {}
    expect(diagnostics).toEqual(expect.objectContaining({
      startIndex: 1,
      skippedSlots: 1,
      slotCount: 4,
      scannedSlots: 2,
      nextStartIndex: 3,
      extractedDomCommentCount: 1,
    }))
    expect(comments.map(comment => comment.text)).toEqual(['第三条新评论'])
  })

  it('extracts Douyin comments from direct comment-list div slots without comment-item markers', async () => {
    document.body.innerHTML = `
      <div id="merge-all-comment-container">
        <span id="count">全部评论(67)</span>
        <div data-e2e="comment-list" id="list">
          <div id="slot-1">
            <a id="avatar-1" href="//www.douyin.com/user/MS4wAvatar"><img alt="嘴大心宽头像"></a>
            <a id="author-1" href="//www.douyin.com/user/MS4wTitle"><span data-click-from="title">嘴大心宽</span></a>
            <div id="body-1"><span>别人的易企秀，怎么可以修改一下变成自己的？</span></div>
            <div id="time-1"><span>6年前</span></div>
            <div class="comment-item-stats-container"><span>回复</span><span>分享</span></div>
          </div>
          <div id="slot-2">
            <a id="author-2" href="//www.douyin.com/user/MS4wOtherTitle"><span data-click-from="title">霞姐一百岁</span></a>
            <div id="body-2"><span>不建议大家用易企秀，慢出心脏病了。</span></div>
            <div id="time-2"><span>2年前</span></div>
            <div class="comment-item-stats-container"><span>回复</span><span>分享</span></div>
          </div>
          <div id="end">暂时没有更多评论</div>
        </div>
      </div>
    `
    mockRect(document.querySelector('#merge-all-comment-container')!, { x: 96, y: 20, width: 670, height: 760 })
    mockRect(document.querySelector('#count')!, { x: 130, y: 58, width: 150, height: 30 })
    mockRect(document.querySelector('#list')!, { x: 98, y: 100, width: 660, height: 650 })
    mockRect(document.querySelector('#slot-1')!, { x: 128, y: 122, width: 610, height: 170 })
    mockRect(document.querySelector('#avatar-1')!, { x: 132, y: 132, width: 58, height: 58 })
    mockRect(document.querySelector('#author-1')!, { x: 230, y: 124, width: 90, height: 28 })
    mockRect(document.querySelector('#body-1')!, { x: 230, y: 164, width: 500, height: 56 })
    mockRect(document.querySelector('#slot-2')!, { x: 128, y: 310, width: 610, height: 170 })
    mockRect(document.querySelector('#author-2')!, { x: 230, y: 318, width: 110, height: 28 })
    mockRect(document.querySelector('#body-2')!, { x: 230, y: 356, width: 500, height: 56 })
    mockRect(document.querySelector('#end')!, { x: 410, y: 520, width: 170, height: 28 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 98, y: 100, width: 660, height: 650 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    const items = result.ok ? result.payload.items as any[] : []
    const diagnostics = result.ok ? result.payload.diagnostics as any : {}
    expect(items.find(item => item.itemType === 'comment_count')).toEqual(expect.objectContaining({
      text: '67',
    }))
    expect(items.find(item => item.itemType === 'comment_end')).toEqual(expect.objectContaining({
      text: '暂时没有更多评论',
    }))
    expect(diagnostics).toEqual(expect.objectContaining({
      selectedListCommentItems: 0,
      selectedListDirectDivs: 3,
      extractedDomCommentCount: 2,
      firstAuthors: expect.arrayContaining(['嘴大心宽']),
      firstTexts: expect.arrayContaining(['别人的易企秀，怎么可以修改一下变成自己的？']),
    }))
    expect(diagnostics.selectedListOuterHtmlSample).toBeUndefined()
    expect(diagnostics.selectedListDirectChildHtmlSamples).toBeUndefined()
    const comments = items.filter(item => item.itemType === 'douyin_comment')
    expect(comments).toEqual([
      expect.objectContaining({
        author: '嘴大心宽',
        text: '别人的易企秀，怎么可以修改一下变成自己的？',
      }),
      expect.objectContaining({
        author: '霞姐一百岁',
        text: '不建议大家用易企秀，慢出心脏病了。',
      }),
    ])
    expect(comments.map(comment => comment.text).join(' ')).not.toContain('分享')
  })

  it('uses requested region key for Douyin DOM extraction when runtime region key is absent', async () => {
    document.body.innerHTML = `
      <div id="merge-all-comment-container">
        <span id="count">全部评论(2)</span>
        <div data-e2e="comment-list" id="list">
          <div id="slot-1">
            <a id="author-1" href="//www.douyin.com/user/MS4wTitle"><span data-click-from="title">嘴大心宽</span></a>
            <div id="body-1"><span>别人的易企秀，怎么可以修改一下变成自己的？</span></div>
            <div class="comment-item-stats-container"><span>回复</span><span>分享</span></div>
          </div>
        </div>
      </div>
    `
    mockRect(document.querySelector('#merge-all-comment-container')!, { x: 96, y: 20, width: 670, height: 760 })
    mockRect(document.querySelector('#count')!, { x: 130, y: 58, width: 150, height: 30 })
    mockRect(document.querySelector('#list')!, { x: 98, y: 100, width: 660, height: 650 })
    mockRect(document.querySelector('#slot-1')!, { x: 128, y: 122, width: 610, height: 170 })
    mockRect(document.querySelector('#author-1')!, { x: 230, y: 124, width: 90, height: 28 })
    mockRect(document.querySelector('#body-1')!, { x: 230, y: 164, width: 500, height: 56 })

    const regions = {
      get: vi.fn(() => ({
        tabId: 9,
        x: 98,
        y: 100,
        width: 660,
        height: 650,
        updatedAt: 1,
      })),
    } as unknown as RegionRegistry
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    const items = result.ok ? result.payload.items as any[] : []
    const diagnostics = result.ok ? result.payload.diagnostics as any : {}
    expect(diagnostics).toEqual(expect.objectContaining({
      requestedRegionKey: 'douyin.comments',
      runtimeRegionKey: undefined,
      effectiveRegionKey: 'douyin.comments',
      injectedProbe: 'douyin_comments_self_contained_v1',
      selectedListDirectDivs: 1,
      extractedDomCommentCount: 1,
    }))
    expect(items.filter(item => item.itemType === 'douyin_comment')).toEqual([
      expect.objectContaining({
        author: '嘴大心宽',
        text: '别人的易企秀，怎么可以修改一下变成自己的？',
      }),
    ])
  })

  it('extracts Douyin comment items before scrolling even when the detected region is misaligned', async () => {
    document.body.innerHTML = `
      <div id="merge-all-comment-container">
        <span id="count" class="BzbFvOrN">全部评论(758)</span>
        <div data-e2e="comment-list" id="list">
          <div class="F89wJ3x4" data-e2e="comment-item" id="item">
            <a id="avatar" href="//www.douyin.com/user/MS4wAvatar"><img alt="欢愉头像"></a>
            <a id="author" href="//www.douyin.com/user/MS4wTitle">
              <span data-click-from="title"><span>欢愉</span></span>
            </a>
            <div data-e2e="video-comment-more">...</div>
            <div class="Sbe6bqNb" id="body">
              <span class="LqTo7UJT">听了半天就是在卖广告<img alt="[尬笑]"></span>
            </div>
            <div class="xVZK2i5x"><span>7月前·广西</span></div>
            <div class="comment-item-stats-container"><span>8</span><div>分享</div><span>回复</span></div>
          </div>
        </div>
      </div>
    `
    mockRect(document.querySelector('#merge-all-comment-container')!, { x: 96, y: 20, width: 670, height: 760 })
    mockRect(document.querySelector('#count')!, { x: 130, y: 58, width: 150, height: 30 })
    mockRect(document.querySelector('#list')!, { x: 98, y: 100, width: 660, height: 650 })
    mockRect(document.querySelector('#item')!, { x: 128, y: 122, width: 610, height: 210 })
    mockRect(document.querySelector('#avatar')!, { x: 132, y: 132, width: 58, height: 58 })
    mockRect(document.querySelector('#author')!, { x: 230, y: 124, width: 90, height: 28 })
    mockRect(document.querySelector('#body')!, { x: 230, y: 164, width: 500, height: 92 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 1200, y: 100, width: 562, height: 558 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    const items = result.ok ? result.payload.items as any[] : []
    expect(items.find(item => item.itemType === 'comment_count')).toEqual(expect.objectContaining({
      text: '758',
    }))
    const comments = items.filter(item => item.itemType === 'douyin_comment')
    expect(comments).toEqual([
      expect.objectContaining({
        author: '欢愉',
        text: '听了半天就是在卖广告[尬笑]',
        href: expect.stringContaining('douyin.com/user/MS4w'),
      }),
    ])
    expect(comments[0].text).not.toContain('分享')
    expect(comments[0].text).not.toContain('回复')
  })

  it('extracts comma-formatted Douyin declared comment count', async () => {
    document.body.innerHTML = `
      <section data-e2e="comment-list" id="list">
        <h2 id="count">全部评论 7,166</h2>
        <div data-e2e="comment-item" id="item">
          <a id="author" href="//www.douyin.com/user/MS4wTitle"><span data-click-from="title">Ly</span></a>
          <div class="LvAtyU_f" id="body">对于99%的人用豆包就行了。</div>
        </div>
      </section>
    `
    mockRect(document.querySelector('#list')!, { x: 1000, y: 80, width: 520, height: 700 })
    mockRect(document.querySelector('#count')!, { x: 1020, y: 90, width: 160, height: 28 })
    mockRect(document.querySelector('#item')!, { x: 1010, y: 130, width: 500, height: 120 })
    mockRect(document.querySelector('#author')!, { x: 1070, y: 138, width: 90, height: 24 })
    mockRect(document.querySelector('#body')!, { x: 1070, y: 172, width: 260, height: 28 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 980, y: 60, width: 560, height: 740 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    const items = result.ok ? result.payload.items as any[] : []
    expect(items).toEqual([
      expect.objectContaining({
        itemType: 'comment_count',
        text: '7166',
      }),
      expect.objectContaining({
        itemType: 'douyin_comment',
        author: 'Ly',
        text: '对于99%的人用豆包就行了。',
      }),
    ])
  })

  it('extracts comments from the current Douyin panel anchors and ignores right rail counts', async () => {
    document.body.innerHTML = `
      <div id="right-rail">
        <div>点赞</div>
        <div id="like-count">11.3万</div>
        <div>评论</div>
        <div id="rail-comment-count">2.8万</div>
        <div>分享</div>
      </div>
      <div class="ume9nyzR" id="merge-all-comment-container">
        <svg class="comment-header-close-btn"></svg>
        <span id="count" class="BzbFvOrN">全部评论(758)</span>
        <div data-e2e="comment-list" class="dXRnaaI9 comment-mainContent St7sSHGo" id="list">
          <div class="F89wJ3x4" data-e2e="comment-item" id="item1">
            <a id="avatar1" href="//www.douyin.com/user/MS4wAvatar1"><img alt="程云飞头像"></a>
            <a id="author1" href="//www.douyin.com/user/MS4wTitle1"><span data-click-from="title">程云飞</span></a>
            <div data-e2e="video-comment-more">...</div>
            <div class="Sbe6bqNb" id="body1"><span class="LqTo7UJT">一个公平公正的平台才是所有人的机遇。</span></div>
            <div class="xVZK2i5x"><span>7月前·IP未知</span></div>
            <div class="comment-item-stats-container"><span>368</span><div>分享</div><span>回复</span></div>
            <button class="comment-reply-expand-btn"><span>展开6条回复</span></button>
          </div>
          <div class="F89wJ3x4" data-e2e="comment-item" id="item2">
            <a id="author2" href="//www.douyin.com/user/MS4wTitle2"><span data-click-from="title">我是啊妹啊</span></a>
            <div class="Sbe6bqNb" id="body2"><span class="LqTo7UJT">普通人也能拥有专业工具，超级个体时代真的来了！</span></div>
            <div class="comment-item-stats-container"><span>17</span><div>分享</div><span>回复</span></div>
          </div>
          <div class="dMP4Jal9"><div class="Hvm3BPqF">加载中</div></div>
          <div id="end-marker">暂时没有更多评论</div>
        </div>
        <div class="comment-input-container"><span>留下你的精彩评论吧</span></div>
      </div>
    `
    mockRect(document.querySelector('#right-rail')!, { x: 1830, y: 120, width: 80, height: 520 })
    mockRect(document.querySelector('#like-count')!, { x: 1840, y: 220, width: 70, height: 28 })
    mockRect(document.querySelector('#rail-comment-count')!, { x: 1840, y: 330, width: 70, height: 28 })
    mockRect(document.querySelector('#merge-all-comment-container')!, { x: 96, y: 20, width: 670, height: 760 })
    mockRect(document.querySelector('#count')!, { x: 130, y: 58, width: 150, height: 30 })
    mockRect(document.querySelector('#list')!, { x: 98, y: 100, width: 660, height: 650 })
    mockRect(document.querySelector('#item1')!, { x: 128, y: 122, width: 610, height: 270 })
    mockRect(document.querySelector('#avatar1')!, { x: 132, y: 132, width: 58, height: 58 })
    mockRect(document.querySelector('#author1')!, { x: 230, y: 124, width: 90, height: 28 })
    mockRect(document.querySelector('#body1')!, { x: 230, y: 164, width: 500, height: 92 })
    mockRect(document.querySelector('#item2')!, { x: 128, y: 410, width: 610, height: 160 })
    mockRect(document.querySelector('#author2')!, { x: 230, y: 416, width: 120, height: 28 })
    mockRect(document.querySelector('#body2')!, { x: 230, y: 456, width: 500, height: 56 })
    mockRect(document.querySelector('#end-marker')!, { x: 410, y: 780, width: 170, height: 28 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.comments', tabId: 9, rect: { x: 98, y: 100, width: 660, height: 650 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(9, { regionKey: 'douyin.comments' }, 1000)

    expect(result.ok).toBe(true)
    const items = result.ok ? result.payload.items as any[] : []
    expect(items.find(item => item.itemType === 'comment_count')).toEqual(expect.objectContaining({
      text: '758',
    }))
    expect(items.find(item => item.itemType === 'comment_end')).toEqual(expect.objectContaining({
      text: '暂时没有更多评论',
    }))
    const comments = items.filter(item => item.itemType === 'douyin_comment')
    expect(comments).toEqual([
      expect.objectContaining({
        author: '程云飞',
        text: '一个公平公正的平台才是所有人的机遇。',
      }),
      expect.objectContaining({
        author: '我是啊妹啊',
        text: '普通人也能拥有专业工具，超级个体时代真的来了！',
      }),
    ])
    expect(items.map(item => item.text)).not.toContain('28000')
    expect(comments.map(item => item.text).join(' ')).not.toContain('分享')
    expect(comments.map(item => item.text).join(' ')).not.toContain('加载中')
  })

  it('extracts Douyin search video results from DOM links in visual order', async () => {
    document.body.innerHTML = `
      <nav>
        <a id="home" href="https://www.douyin.com/">首页</a>
      </nav>
      <main>
        <a id="first" href="https://www.douyin.com/video/1111111111111">
          <img alt="cover">
          <span>全网都在养的“龙虾”，它能做什么？#记者实测OpenClaw</span>
          <span>55.7万</span>
        </a>
        <a id="second" href="https://www.douyin.com/video/2222222222222">
          <img alt="cover">
          <span>第二个 OpenClaw 视频</span>
          <span>7.2万</span>
        </a>
      </main>
    `
    mockRect(document.querySelector('#home')!, { x: 24, y: 96, width: 96, height: 28 })
    mockRect(document.querySelector('#first')!, { x: 190, y: 170, width: 245, height: 430 })
    mockRect(document.querySelector('#second')!, { x: 462, y: 168, width: 245, height: 430 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.search_results', tabId: 10, rect: { x: 0, y: 0, width: 1920, height: 900 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(10, { regionKey: 'douyin.search_results' }, 1000)

    expect(result.ok).toBe(true)
    expect(result.ok && result.payload.items).toEqual([
      expect.objectContaining({
        itemType: 'douyin_video_result',
        href: 'https://www.douyin.com/video/1111111111111',
        text: expect.stringContaining('记者实测OpenClaw'),
        bbox: { x: 190, y: 170, width: 245, height: 430 },
      }),
      expect.objectContaining({
        itemType: 'douyin_video_result',
        href: 'https://www.douyin.com/video/2222222222222',
      }),
    ])
  })

  it('orders Douyin search cards row-major when DOM order differs from visual grid', async () => {
    document.body.innerHTML = `
      <main>
        <a id="second" href="https://www.douyin.com/video/2222222222222">右侧第二条 OpenClaw 视频</a>
        <a id="first" href="https://www.douyin.com/video/1111111111111">左侧第一条 OpenClaw 视频</a>
        <a id="third" href="https://www.douyin.com/video/3333333333333">第二行第一条 OpenClaw 视频</a>
      </main>
    `
    mockRect(document.querySelector('#second')!, { x: 462, y: 168, width: 245, height: 430 })
    mockRect(document.querySelector('#first')!, { x: 190, y: 170, width: 245, height: 430 })
    mockRect(document.querySelector('#third')!, { x: 190, y: 620, width: 245, height: 430 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.search_results', tabId: 11, rect: { x: 0, y: 0, width: 1920, height: 1100 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(11, { regionKey: 'douyin.search_results' }, 1000)

    expect(result.ok).toBe(true)
    const hrefs = result.ok ? result.payload.items.map((item: any) => item.href) : []
    expect(hrefs).toEqual([
      'https://www.douyin.com/video/1111111111111',
      'https://www.douyin.com/video/2222222222222',
      'https://www.douyin.com/video/3333333333333',
    ])
  })

  it('extracts clickable Douyin search cards even when no video href is exposed', async () => {
    document.body.innerHTML = `
      <main>
        <article id="first" data-e2e="search-video-card">
          <div class="cover"></div>
          <span>我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理 #openclaw</span>
          <span>39.6万</span>
        </article>
        <article id="second" data-e2e="search-video-card">
          <div class="cover"></div>
          <span>一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？</span>
          <span>15.2万</span>
        </article>
      </main>
    `
    mockRect(document.querySelector('#first')!, { x: 190, y: 158, width: 245, height: 430 })
    mockRect(document.querySelector('#second')!, { x: 462, y: 158, width: 245, height: 430 })

    const regions = new RegionRegistry()
    regions.register({ key: 'douyin.search_results', tabId: 12, rect: { x: 0, y: 0, width: 1920, height: 900 } })
    const handler = extractRegionHandler({ regions, chrome: chromeWithDomExecution() })

    const result = await handler(12, { regionKey: 'douyin.search_results' }, 1000)

    expect(result.ok).toBe(true)
    const items = result.ok ? result.payload.items as any[] : []
    expect(items).toEqual([
      expect.objectContaining({
        itemType: 'douyin_video_result',
        href: undefined,
        text: expect.stringContaining('我有自己的AI助理啦'),
        bbox: { x: 190, y: 158, width: 245, height: 430 },
      }),
      expect.objectContaining({
        itemType: 'douyin_video_result',
        text: expect.stringContaining('一个视频搞懂OpenClaw'),
      }),
    ])
  })
})
