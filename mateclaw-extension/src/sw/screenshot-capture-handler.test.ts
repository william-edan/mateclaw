import { describe, expect, it, vi } from 'vitest'
import { ScreenshotCaptureHandler } from './screenshot-capture-handler'
import { EdgeMessageKind, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'
import type { DebuggerManager } from './debugger-manager'

// Capture now goes over CDP Page.captureScreenshot (chrome.debugger), not
// chrome.tabs.captureVisibleTab. chrome.tabs.get is still used (metadata only)
// for the viewport dims.
function fakeChrome(opts: { tab?: Partial<chrome.tabs.Tab> } = {}) {
  const get = vi.fn(async () => ({
    id: 42,
    windowId: 7,
    width: 1280,
    height: 800,
    ...opts.tab,
  }))
  return { tabs: { get } } as unknown as typeof globalThis.chrome
}

// `metrics: 'omit'` makes Page.getLayoutMetrics return {} so the handler can't
// build a CSS-px clip and falls back to a plain (native-DPR) capture + tab dims.
function fakeDebugger(
  opts: { data?: string; sendReject?: unknown; metrics?: unknown | 'omit' } = {},
) {
  const calls: Array<{ tabId: number; method: string; params: unknown }> = []
  const attach = vi.fn(async () => {})
  const send = vi.fn(async (tabId: number, method: string, params: unknown) => {
    calls.push({ tabId, method, params })
    if (method === 'Page.getLayoutMetrics') {
      if (opts.metrics === 'omit') return {}
      return (
        opts.metrics ?? {
          cssLayoutViewport: { pageX: 0, pageY: 0, clientWidth: 1280, clientHeight: 800 },
          cssVisualViewport: {
            offsetX: 0, offsetY: 0, pageX: 0, pageY: 0,
            clientWidth: 1280, clientHeight: 800, scale: 1,
          },
        }
      )
    }
    if (method === 'Page.captureScreenshot') {
      if (opts.sendReject) throw opts.sendReject
      return { data: opts.data ?? 'iVBORw0KGgoAAAANS' }
    }
    return {}
  })
  const debuggerManager = {
    attach,
    send,
    detach: vi.fn(async () => {}),
    scheduleIdleDetach: vi.fn(),
    isAttached: () => true,
  } as unknown as DebuggerManager
  return { debuggerManager, attach, send, calls }
}

function fakeResolver(resolveImpl: (tabRef: TabRef) => Promise<number | null> = async () => 42) {
  return { resolve: vi.fn(resolveImpl) } as unknown as TabRefResolver
}

function screenshotRequest(
  payload: Record<string, unknown> = {},
  overrides: Partial<EdgeMessage> = {},
): EdgeMessage {
  return {
    v: 1,
    msg_id: 'request-1',
    kind: EdgeMessageKind.ScreenshotCaptureRequest,
    ts: 0,
    trace_id: 'trace-1',
    session_id: 'must-not-propagate',
    payload: { tab_ref: 'main', ...payload },
    ...overrides,
  }
}

function makeHandler(opts: {
  resolver?: TabRefResolver
  chrome?: typeof globalThis.chrome
  debuggerManager?: DebuggerManager
  uuid?: () => string
  clock?: () => number
} = {}) {
  const sentUp: EdgeMessage[] = []
  const chrome = opts.chrome ?? fakeChrome()
  const resolver = opts.resolver ?? fakeResolver()
  const debuggerManager = opts.debuggerManager ?? fakeDebugger().debuggerManager
  const handler = new ScreenshotCaptureHandler({
    resolver,
    debuggerManager,
    chrome,
    sendUp: msg => sentUp.push(msg),
    uuid: opts.uuid ?? (() => 'shot-1'),
    clock: opts.clock ?? (() => 1730000000123),
  })
  return { handler, sentUp, chrome, resolver, debuggerManager }
}

describe('ScreenshotCaptureHandler', () => {
  it('happy path: captures via CDP Page.captureScreenshot (jpeg, scale:1 clip) + viewport metadata', async () => {
    const { debuggerManager, send } = fakeDebugger()
    const { handler, sentUp } = makeHandler({ debuggerManager })

    await handler.handle(screenshotRequest())

    // Reads the CSS viewport then captures at scale:1 over a clip — so image px
    // == CSS px regardless of the display DPR (NOT chrome.tabs.captureVisibleTab).
    expect(send).toHaveBeenCalledWith(42, 'Page.getLayoutMetrics', {})
    expect(send).toHaveBeenCalledWith(42, 'Page.captureScreenshot', {
      format: 'jpeg',
      quality: 70,
      clip: { x: 0, y: 0, width: 1280, height: 800, scale: 1 },
      captureBeyondViewport: false,
    })
    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.kind).toBe(EdgeMessageKind.ScreenshotCaptureResponse)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'shot-1',
      captured_at_ms: 1730000000123,
      tab_ref: 42,
      format: 'jpeg',
      data_base64: 'iVBORw0KGgoAAAANS',
      viewport: { w: 1280, h: 800 },
      actual_dimensions: { w: 1280, h: 800 },
    })
  })

  it('tab_ref cannot resolve -> NO_TARGET_TAB without capturing', async () => {
    const resolver = fakeResolver(async () => null)
    const { debuggerManager, send } = fakeDebugger()
    const { handler, sentUp } = makeHandler({ resolver, debuggerManager })

    await handler.handle(screenshotRequest({ tab_ref: 'active' }))

    expect(send).not.toHaveBeenCalled()
    expect(sentUp[0]!.payload).toMatchObject({ tab_ref: -1, error: { code: 'NO_TARGET_TAB' } })
  })

  it('malformed tab_ref -> NO_TARGET_TAB without resolver/capture', async () => {
    const resolver = fakeResolver()
    const { debuggerManager, send } = fakeDebugger()
    const { handler, sentUp } = makeHandler({ resolver, debuggerManager })

    await handler.handle(screenshotRequest({ tab_ref: 'sidebar' }))

    expect(resolver.resolve).not.toHaveBeenCalled()
    expect(send).not.toHaveBeenCalled()
    expect(sentUp[0]!.payload).toMatchObject({ tab_ref: -1, error: { code: 'NO_TARGET_TAB' } })
  })

  it('CDP send rejection -> PERMISSION_DENIED', async () => {
    const { debuggerManager } = fakeDebugger({ sendReject: new Error('debugger detached') })
    const { handler, sentUp } = makeHandler({ debuggerManager })

    await handler.handle(screenshotRequest())

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      tab_ref: -1,
      error: { code: 'PERMISSION_DENIED', message: 'debugger detached' },
    })
  })

  it('empty screenshot data -> PERMISSION_DENIED', async () => {
    const { debuggerManager } = fakeDebugger({ data: '' })
    const { handler, sentUp } = makeHandler({ debuggerManager })

    await handler.handle(screenshotRequest())

    expect(sentUp[0]!.payload).toMatchObject({ tab_ref: -1, error: { code: 'PERMISSION_DENIED' } })
  })

  it('base64 above the 2 MB cap -> SCREENSHOT_TOO_LARGE', async () => {
    const { debuggerManager } = fakeDebugger({ data: 'a'.repeat(2_000_001) })
    const { handler, sentUp } = makeHandler({ debuggerManager })

    await handler.handle(screenshotRequest())

    expect(sentUp[0]!.payload).toMatchObject({ tab_ref: -1, error: { code: 'SCREENSHOT_TOO_LARGE' } })
    expect(String((sentUp[0]!.payload!.error as Record<string, unknown>).message)).toContain('2000001')
  })

  it('no layout metrics -> plain (native-DPR) capture + tab dims fallback', async () => {
    const { debuggerManager, send } = fakeDebugger({ metrics: 'omit' })
    const chrome = fakeChrome({ tab: { width: 1024, height: 768 } })
    const { handler, sentUp } = makeHandler({ debuggerManager, chrome })

    await handler.handle(screenshotRequest())

    // No CSS viewport → capture WITHOUT a clip, and report tab metadata dims.
    expect(send).toHaveBeenCalledWith(42, 'Page.captureScreenshot', { format: 'jpeg', quality: 70 })
    expect(sentUp[0]!.payload).toMatchObject({
      format: 'jpeg',
      viewport: { w: 1024, h: 768 },
      actual_dimensions: { w: 1024, h: 768 },
    })
  })

  it('scrolled page -> clip uses the visual-viewport page offset (captures what is visible)', async () => {
    const { debuggerManager, send } = fakeDebugger({
      metrics: {
        cssLayoutViewport: { pageX: 0, pageY: 0, clientWidth: 1280, clientHeight: 800 },
        cssVisualViewport: {
          offsetX: 0, offsetY: 0, pageX: 0, pageY: 1200,
          clientWidth: 1280, clientHeight: 800, scale: 1,
        },
      },
    })
    const { handler } = makeHandler({ debuggerManager })

    await handler.handle(screenshotRequest())

    expect(send).toHaveBeenCalledWith(42, 'Page.captureScreenshot', {
      format: 'jpeg',
      quality: 70,
      clip: { x: 0, y: 1200, width: 1280, height: 800, scale: 1 },
      captureBeyondViewport: false,
    })
  })

  it('in_reply_to and trace_id pass through from request', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(screenshotRequest({}, { msg_id: 'request-xyz', trace_id: 'trace-xyz' }))

    expect(sentUp[0]!.in_reply_to).toBe('request-xyz')
    expect(sentUp[0]!.trace_id).toBe('trace-xyz')
  })

  it('session_id="" on outbound (P0-1 invariant)', async () => {
    const { handler, sentUp } = makeHandler()
    await handler.handle(screenshotRequest())
    expect(sentUp[0]!.session_id).toBe('')
  })

  it('ignores non-screenshot messages', async () => {
    const { debuggerManager, send } = fakeDebugger()
    const { handler, sentUp } = makeHandler({ debuggerManager })

    await handler.handle({ ...screenshotRequest(), kind: EdgeMessageKind.Ping })

    expect(send).not.toHaveBeenCalled()
    expect(sentUp).toHaveLength(0)
  })
})
