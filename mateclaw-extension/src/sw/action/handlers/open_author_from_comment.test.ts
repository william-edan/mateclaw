import { describe, expect, it, vi } from 'vitest'
import { openAuthorFromCommentHandler } from './open_author_from_comment'

describe('openAuthorFromCommentHandler', () => {
  it('tracks and groups the opened author tab under the MateClaw subject', async () => {
    const create = vi.fn(async () => ({ id: 77 }))
    const get = vi.fn(async () => ({ id: 42, windowId: 100 }))
    const addTab = vi.fn(async () => undefined)
    const joinChromeGroup = vi.fn(async () => 12)
    const handler = openAuthorFromCommentHandler({
      chrome: {
        scripting: { executeScript: vi.fn() },
        tabs: { create, get },
      } as unknown as typeof chrome,
      tabGroupManager: { addTab, joinChromeGroup },
      subject: 'default',
    })

    const result = await handler(42, {
      commentText: '慢出心脏病',
      authorName: '霞姐一百岁',
      authorProfileUrl: 'https://www.douyin.com/user/MS4wLjABAAAA-test',
    }, 5000)

    expect(create).toHaveBeenCalledExactlyOnceWith({
      url: 'https://www.douyin.com/user/MS4wLjABAAAA-test',
      active: false,
      openerTabId: 42,
      windowId: 100,
    })
    expect(addTab).toHaveBeenCalledExactlyOnceWith('default', 77)
    expect(joinChromeGroup).toHaveBeenCalledExactlyOnceWith('default', 77)
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.payload).toMatchObject({
        href: 'https://www.douyin.com/user/MS4wLjABAAAA-test',
        author: '霞姐一百岁',
        tabId: 77,
      })
    }
  })

  it('still groups the tab when Chrome rejects openerTabId', async () => {
    const create = vi.fn()
      .mockRejectedValueOnce(new Error('Tab opener must be in the same window as the updated tab.'))
      .mockResolvedValueOnce({ id: 78 })
    const get = vi.fn(async () => ({ id: 42, windowId: 100 }))
    const addTab = vi.fn(async () => undefined)
    const joinChromeGroup = vi.fn(async () => 12)
    const handler = openAuthorFromCommentHandler({
      chrome: {
        scripting: { executeScript: vi.fn() },
        tabs: { create, get },
      } as unknown as typeof chrome,
      tabGroupManager: { addTab, joinChromeGroup },
      subject: 'default',
    })

    const result = await handler(42, {
      commentText: '慢出心脏病',
      authorName: '霞姐一百岁',
      authorProfileUrl: 'https://www.douyin.com/user/MS4wLjABAAAA-test',
    }, 5000)

    expect(create).toHaveBeenNthCalledWith(1, {
      url: 'https://www.douyin.com/user/MS4wLjABAAAA-test',
      active: false,
      openerTabId: 42,
      windowId: 100,
    })
    expect(create).toHaveBeenNthCalledWith(2, {
      url: 'https://www.douyin.com/user/MS4wLjABAAAA-test',
      active: false,
      windowId: 100,
    })
    expect(addTab).toHaveBeenCalledExactlyOnceWith('default', 78)
    expect(joinChromeGroup).toHaveBeenCalledExactlyOnceWith('default', 78)
    expect(result.ok).toBe(true)
  })
})
