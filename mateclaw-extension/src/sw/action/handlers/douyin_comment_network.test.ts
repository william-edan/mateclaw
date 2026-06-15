import { describe, expect, it, vi } from 'vitest'
import { SessionDetachedError, type DebuggerEvent, type DebuggerManager } from '../../debugger-manager'
import { douyinCommentNetworkHandler } from './douyin_comment_network'
import type {
  NetworkLoadingFinishedEvent,
  NetworkResponseReceivedEvent,
} from '../../cdp-types'

function fakeDebugger() {
  let listener: ((event: DebuggerEvent) => void) | undefined
  const bodies = new Map<string, { body: string; base64Encoded: boolean }>()
  const removeListener = vi.fn()
  const attach = vi.fn(async () => {})
  const send = vi.fn(async (_tabId: number, method: string, params?: unknown) => {
    if (method === 'Network.getResponseBody') {
      const requestId = (params as { requestId?: string } | undefined)?.requestId
      return bodies.get(String(requestId)) ?? { body: '', base64Encoded: false }
    }
    return {}
  })
  const addEventListener = vi.fn((_tabId: number, fn: (event: DebuggerEvent) => void) => {
    listener = fn
    return removeListener
  })

  return {
    debugger: { attach, send, addEventListener } as unknown as DebuggerManager,
    attach,
    send,
    removeListener,
    setBody: (requestId: string, body: string, base64Encoded = false) => {
      bodies.set(requestId, { body, base64Encoded })
    },
    emit: (tabId: number, method: DebuggerEvent['method'], params: DebuggerEvent['params']) => {
      listener?.({ tabId, method, params } as DebuggerEvent)
    },
    emitWithSession: (
      sessionId: string,
      tabId: number,
      method: DebuggerEvent['method'],
      params: DebuggerEvent['params'],
    ) => {
      listener?.({ tabId, sessionId, method, params } as DebuggerEvent)
    },
  }
}

function responseReceived(
  requestId: string,
  url: string,
  overrides: Partial<NetworkResponseReceivedEvent['response']> = {},
): NetworkResponseReceivedEvent {
  return {
    requestId,
    response: {
      url,
      status: 200,
      mimeType: 'application/json',
      ...overrides,
    },
  }
}

function loadingFinished(requestId: string, encodedDataLength: number): NetworkLoadingFinishedEvent {
  return { requestId, encodedDataLength }
}

function commentJson(id = '1'): string {
  return JSON.stringify({
    comments: [{ cid: id, text: `comment ${id}` }],
    cursor: Number(id),
    has_more: true,
    aweme_id: 'aweme-1',
  })
}

describe('douyin_comment_network handler', () => {
  it('start enables Network, drain returns and clears captured pages, stop disables Network', async () => {
    const env = fakeDebugger()
    let now = 1_000
    const handler = douyinCommentNetworkHandler({
      debugger: env.debugger,
      clock: () => now,
      setTimer: vi.fn(() => 1),
      clearTimer: vi.fn(),
    })

    const start = await handler(42, {
      op: 'start',
      maxPages: 3,
      maxBodyBytes: 2_000,
      ttlMs: 10_000,
    }, 5_000)

    expect(start.ok).toBe(true)
    expect(env.attach).toHaveBeenCalledExactlyOnceWith(42)
    expect(env.send.mock.calls.some(call =>
      call[0] === 42 &&
      call[1] === 'Target.setAutoAttach' &&
      (call[2] as { autoAttach?: boolean; flatten?: boolean }).autoAttach === true &&
      (call[2] as { autoAttach?: boolean; flatten?: boolean }).flatten === true
    )).toBe(true)
    expect(env.send.mock.calls.some(call =>
      call[0] === 42 &&
      call[1] === 'Network.enable' &&
      (call[2] as { maxResourceBufferSize?: number; maxPostDataSize?: number }).maxResourceBufferSize === 2_000 &&
      (call[2] as { maxResourceBufferSize?: number; maxPostDataSize?: number }).maxPostDataSize === 0
    )).toBe(true)

    env.setBody('r-url', commentJson('1'))
    env.emit(42, 'Network.responseReceived', responseReceived(
      'r-url',
      'https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=1&cursor=0',
    ))
    env.emit(42, 'Network.loadingFinished', loadingFinished('r-url', 180))

    env.setBody('r-json', commentJson('2'))
    env.emit(42, 'Network.responseReceived', responseReceived(
      'r-json',
      'https://www.douyin.com/api/resource',
    ))
    env.emit(42, 'Network.loadingFinished', loadingFinished('r-json', 180))

    now = 1_100
    const drain = await handler(42, { op: 'drain' }, 5_000)

    expect(drain.ok).toBe(true)
    const pages = drain.ok ? drain.payload.pages as Array<Record<string, unknown>> : []
    expect(pages).toHaveLength(2)
    expect(pages[0]).toEqual(expect.objectContaining({
      url: expect.stringContaining('/comment/list/'),
      requestId: 'r-url',
      status: 200,
      body: expect.stringContaining('"comments"'),
      base64Encoded: false,
      capturedAtMs: 1_100,
    }))
    expect(pages[1]).toEqual(expect.objectContaining({
      requestId: 'r-json',
      body: expect.stringContaining('"aweme_id"'),
    }))

    const drainAgain = await handler(42, { op: 'drain' }, 5_000)
    expect(drainAgain.ok && drainAgain.payload.pages).toEqual([])

    const stop = await handler(42, { op: 'stop' }, 5_000)

    expect(stop.ok).toBe(true)
    expect(env.send.mock.calls.some(call => call[0] === 42 && call[1] === 'Network.disable')).toBe(true)
    expect(env.send.mock.calls.some(call =>
      call[0] === 42 &&
      call[1] === 'Target.setAutoAttach' &&
      (call[2] as { autoAttach?: boolean }).autoAttach === false
    )).toBe(true)
    expect(env.removeListener).toHaveBeenCalledOnce()
  })

  it('captures comment pages from auto-attached child targets by sessionId', async () => {
    const env = fakeDebugger()
    const handler = douyinCommentNetworkHandler({
      debugger: env.debugger,
      setTimer: vi.fn(() => 1),
      clearTimer: vi.fn(),
    })

    await handler(42, { op: 'start', maxPages: 3, maxBodyBytes: 2_000, ttlMs: 10_000 }, 5_000)
    env.emit(42, 'Target.attachedToTarget', {
      sessionId: 'child-1',
      targetInfo: {
        targetId: 'target-1',
        type: 'iframe',
        url: 'https://www.douyin.com/',
      },
    })
    await Promise.resolve()

    expect(env.send.mock.calls.some(call =>
      call[0] === 42 &&
      call[1] === 'Network.enable' &&
      call[3] === 'child-1'
    )).toBe(true)

    env.setBody('r-child', commentJson('child'))
    env.emitWithSession('child-1', 42, 'Network.responseReceived', responseReceived(
      'r-child',
      'https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=1&cursor=child',
    ))
    env.emitWithSession('child-1', 42, 'Network.loadingFinished', loadingFinished('r-child', 200))

    const drain = await handler(42, { op: 'drain' }, 5_000)
    const pages = drain.ok ? drain.payload.pages as Array<Record<string, unknown>> : []
    expect(pages).toHaveLength(1)
    expect(pages[0]).toEqual(expect.objectContaining({
      requestId: 'r-child',
      sessionId: 'child-1',
      targetType: 'iframe',
      body: expect.stringContaining('"comments"'),
    }))
  })

  it('enforces maxPages and maxBodyBytes before storing responses', async () => {
    const env = fakeDebugger()
    const handler = douyinCommentNetworkHandler({
      debugger: env.debugger,
      setTimer: vi.fn(() => 1),
      clearTimer: vi.fn(),
    })

    await handler(42, { op: 'start', maxPages: 2, maxBodyBytes: 500, ttlMs: 10_000 }, 5_000)
    for (const id of ['r1', 'r2', 'r3']) {
      env.setBody(id, commentJson(id.slice(1)))
      env.emit(42, 'Network.responseReceived', responseReceived(
        id,
        `https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=1&cursor=${id}`,
      ))
      env.emit(42, 'Network.loadingFinished', loadingFinished(id, 120))
    }

    const quantityDrain = await handler(42, { op: 'drain' }, 5_000)
    const quantityPages = quantityDrain.ok ? quantityDrain.payload.pages as Array<Record<string, unknown>> : []
    expect(quantityPages.map(page => page.requestId)).toEqual(['r1', 'r2'])

    await handler(42, { op: 'start', maxPages: 5, maxBodyBytes: 30, ttlMs: 10_000 }, 5_000)
    env.setBody('large-encoded', commentJson('9'))
    env.emit(42, 'Network.responseReceived', responseReceived(
      'large-encoded',
      'https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=1&cursor=9',
    ))
    env.emit(42, 'Network.loadingFinished', loadingFinished('large-encoded', 31))

    env.setBody('large-body', commentJson('10'))
    env.emit(42, 'Network.responseReceived', responseReceived(
      'large-body',
      'https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=1&cursor=10',
    ))
    env.emit(42, 'Network.loadingFinished', loadingFinished('large-body', 20))

    const sizeDrain = await handler(42, { op: 'drain' }, 5_000)
    expect(sizeDrain.ok && sizeDrain.payload.pages).toEqual([])
    const bodyReads = env.send.mock.calls.filter(call => call[1] === 'Network.getResponseBody')
    expect(bodyReads.map(call => (call[2] as { requestId: string }).requestId)).not.toContain('large-encoded')
    expect(bodyReads.map(call => (call[2] as { requestId: string }).requestId)).toContain('large-body')
  })

  it('filters non-comment responses even when they are small JSON responses', async () => {
    const env = fakeDebugger()
    const handler = douyinCommentNetworkHandler({
      debugger: env.debugger,
      setTimer: vi.fn(() => 1),
      clearTimer: vi.fn(),
    })

    await handler(42, { op: 'start', maxPages: 5, maxBodyBytes: 1_000, ttlMs: 10_000 }, 5_000)
    env.setBody('plain-json', JSON.stringify({ items: [{ name: 'plain' }], page: { cursor: 1 } }))
    env.emit(42, 'Network.responseReceived', responseReceived(
      'plain-json',
      'https://www.example.com/api/list',
    ))
    env.emit(42, 'Network.loadingFinished', loadingFinished('plain-json', 80))

    env.setBody('stylesheet', 'body{}')
    env.emit(42, 'Network.responseReceived', responseReceived(
      'stylesheet',
      'https://www.douyin.com/static/app.css',
      { mimeType: 'text/css' },
    ))
    env.emit(42, 'Network.loadingFinished', loadingFinished('stylesheet', 8))

    const drain = await handler(42, { op: 'drain' }, 5_000)

    expect(drain.ok && drain.payload.pages).toEqual([])
    expect(env.send.mock.calls.some(call => call[1] === 'Network.getResponseBody' && (call[2] as { requestId: string }).requestId === 'stylesheet')).toBe(false)
  })

  it('maps debugger/devtools detach errors to the existing DEVTOOLS_OPEN style', async () => {
    const env = fakeDebugger()
    env.attach.mockRejectedValueOnce(new SessionDetachedError(42, 'canceled_by_user'))
    const handler = douyinCommentNetworkHandler({
      debugger: env.debugger,
      setTimer: vi.fn(() => 1),
      clearTimer: vi.fn(),
    })

    await expect(handler(42, { op: 'start' }, 5_000)).rejects.toMatchObject({
      code: 'DEVTOOLS_OPEN',
      retryable: true,
    })
  })
})
