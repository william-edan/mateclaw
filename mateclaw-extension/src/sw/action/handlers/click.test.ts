import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { ActionFailureError } from '../ActionExecutor'
import { clickHandler } from './click'

// 临时调试开关 DOM_ONLY_NO_CDP_FALLBACK 生产默认 true(只走 DOM、不回退 CDP)。
// 本测试套件覆盖的是"DOM 失败 → CDP 兜底"旧行为,故在测试中关掉该开关。见 debug-flags.ts。
vi.mock('./debug-flags', () => ({ DOM_ONLY_NO_CDP_FALLBACK: false }))

function fakeDebugger() {
  const sent: Array<{ tabId: number; method: string; params: any }> = []
  const calls: string[] = []
  const debuggerStub = {
    attach: vi.fn(async () => {
      calls.push('attach')
    }),
    detach: vi.fn(async () => {}),
    send: vi.fn(async (tabId: number, method: string, params: any) => {
      calls.push(method)
      sent.push({ tabId, method, params })
      return {}
    }),
    isAttached: () => true,
  } as unknown as DebuggerManager

  return { debuggerStub, sent, calls }
}

function mouseParams(sent: Array<{ tabId: number; method: string; params: any }>) {
  return sent.map(entry => entry.params)
}

describe('click handler', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('single left click sends mousePressed + mouseReleased at (x,y)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0 })

    const result = await handler(42, { x: 10, y: 20, button: 'left', click_count: 1 }, 5000)

    expect(result.ok).toBe(true)
    expect(sent).toHaveLength(2)
    expect(sent.map(entry => entry.method)).toEqual(['Input.dispatchMouseEvent', 'Input.dispatchMouseEvent'])
    expect(mouseParams(sent)).toEqual([
      { type: 'mousePressed', x: 10, y: 20, button: 'left', clickCount: 1, modifiers: 0 },
      { type: 'mouseReleased', x: 10, y: 20, button: 'left', clickCount: 1, modifiers: 0 },
    ])
  })

  it('right-button click sets button=right on both events', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0 })

    await handler(42, { x: 10, y: 20, button: 'right' }, 5000)

    expect(mouseParams(sent).map(params => params.button)).toEqual(['right', 'right'])
  })

  it('middle-button click sets button=middle', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0 })

    await handler(42, { x: 10, y: 20, button: 'middle' }, 5000)

    expect(mouseParams(sent).map(params => params.button)).toEqual(['middle', 'middle'])
  })

  it('double-click sends 2x (pressed,released) with clickCount escalating 1->2', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = clickHandler({
      debugger: debuggerStub,
      pressHoldMs: () => 0,
    })

    await handler(42, { x: 10, y: 20, click_count: 2 }, 5000)

    expect(mouseParams(sent).map(params => [params.type, params.clickCount])).toEqual([
      ['mousePressed', 1],
      ['mouseReleased', 1],
      ['mousePressed', 2],
      ['mouseReleased', 2],
    ])
  })

  it('triple-click sends 3x with clickCount 1->2->3', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = clickHandler({
      debugger: debuggerStub,
      pressHoldMs: () => 0,
    })

    await handler(42, { x: 10, y: 20, click_count: 3 }, 5000)

    expect(mouseParams(sent).map(params => [params.type, params.clickCount])).toEqual([
      ['mousePressed', 1],
      ['mouseReleased', 1],
      ['mousePressed', 2],
      ['mouseReleased', 2],
      ['mousePressed', 3],
      ['mouseReleased', 3],
    ])
  })

  it('uses injected clock + random for deterministic hold times', async () => {
    vi.useFakeTimers()
    const { debuggerStub, sent } = fakeDebugger()
    const clock = vi.fn(() => 10_000)
    const random = vi.fn(() => 0.5)
    const handler = clickHandler({ debugger: debuggerStub, clock, random })

    const pending = handler(42, { x: 10, y: 20 }, 5000)

    await Promise.resolve()
    await Promise.resolve()
    expect(sent).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(35)
    expect(sent).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    const result = await pending

    expect(result.ok).toBe(true)
    expect(sent).toHaveLength(2)
    expect(clock).toHaveBeenCalled()
    expect(random).toHaveBeenCalled()
  })

  it('awaits settleAfterClick AFTER dispatching the click (so an opened panel renders before observe)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    let sentCountAtSettle = -1
    const settleAfterClick = vi.fn(async () => {
      sentCountAtSettle = sent.length
    })
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0, settleAfterClick })

    const result = await handler(42, { x: 10, y: 20 }, 5000)

    expect(result.ok).toBe(true)
    expect(settleAfterClick).toHaveBeenCalledExactlyOnceWith(42)
    // settle runs only after BOTH mouse events were dispatched.
    expect(sentCountAtSettle).toBe(2)
  })

  it('a settleAfterClick that throws does NOT fail a click that already landed', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const settleAfterClick = vi.fn(async () => {
      throw new Error('settle boom')
    })
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0, settleAfterClick })

    const result = await handler(42, { x: 10, y: 20 }, 5000)

    expect(result.ok).toBe(true)
    expect(sent).toHaveLength(2)
  })

  it('throws SESSION_DETACHED when DebuggerManager.send throws SessionDetachedError', async () => {
    const { debuggerStub } = fakeDebugger()
    vi.mocked(debuggerStub.send).mockRejectedValueOnce(new SessionDetachedError(42, 'target_closed'))
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0 })

    await expect(handler(42, { x: 10, y: 20 }, 5000)).rejects.toMatchObject({
      name: 'ActionFailureError',
      code: 'SESSION_DETACHED',
      retryable: true,
    } satisfies Partial<ActionFailureError>)
  })

  it('default button=left when not specified', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0 })

    await handler(42, { x: 10, y: 20 }, 5000)

    expect(mouseParams(sent).map(params => params.button)).toEqual(['left', 'left'])
  })

  it('default click_count=1 when not specified', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0 })

    await handler(42, { x: 10, y: 20 }, 5000)

    expect(mouseParams(sent).map(params => params.clickCount)).toEqual([1, 1])
  })

  it('attaches to tab before first event', async () => {
    const { debuggerStub, calls } = fakeDebugger()
    const handler = clickHandler({ debugger: debuggerStub, pressHoldMs: () => 0 })

    await handler(42, { x: 10, y: 20 }, 5000)

    expect(debuggerStub.attach).toHaveBeenCalledExactlyOnceWith(42)
    expect(calls).toEqual(['attach', 'Input.dispatchMouseEvent', 'Input.dispatchMouseEvent'])
  })
})
