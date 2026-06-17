import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionDetachedError } from '../../debugger-manager'
import { ActionFailureError } from '../ActionExecutor'
import type { DebuggerManager } from '../../debugger-manager'
import type { CDP } from '../../cdp-types'
import { scrollHandler } from './scroll'

// 临时调试开关 DOM_ONLY_NO_CDP_FALLBACK 生产默认 true(只走 DOM、不回退 CDP)。
// 本测试套件覆盖的是"DOM 失败 → CDP 兜底"旧行为,故在测试中关掉该开关。见 debug-flags.ts。
vi.mock('./debug-flags', () => ({ DOM_ONLY_NO_CDP_FALLBACK: false }))

type WheelParams = CDP['Input.dispatchMouseEvent']['params']

function makeDebugger() {
  return {
    attach: vi.fn(async () => {}),
    send: vi.fn(async () => ({})),
  } as unknown as Pick<DebuggerManager, 'attach' | 'send'>
}

function sentWheelParams(debuggerManager: Pick<DebuggerManager, 'attach' | 'send'>) {
  return vi.mocked(debuggerManager.send).mock.calls.map(call => call[2] as WheelParams)
}

describe('scroll handler', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('scroll down 500px in 5 segments sends 5 wheel events with deltaY=100', async () => {
    const debuggerManager = makeDebugger()
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    const result = await handler(42, { direction: 'down', distance_px: 500, segments: 5 }, 5000)

    expect(result.ok).toBe(true)
    expect(debuggerManager.attach).toHaveBeenCalledExactlyOnceWith(42)
    expect(debuggerManager.send).toHaveBeenCalledTimes(5)
    expect(vi.mocked(debuggerManager.send).mock.calls.map(call => call[1]))
      .toEqual(Array.from({ length: 5 }, () => 'Input.dispatchMouseEvent'))
    expect(sentWheelParams(debuggerManager)).toEqual(Array.from({ length: 5 }, () => ({
      type: 'mouseWheel',
      x: 640,
      y: 400,
      deltaX: 0,
      deltaY: 100,
      modifiers: 0,
    })))
  })

  it('scroll up 500px sends 5 wheel events with deltaY=-100', async () => {
    const debuggerManager = makeDebugger()
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    await handler(42, { direction: 'up', distance_px: 500, segments: 5 }, 5000)

    expect(sentWheelParams(debuggerManager).map(params => params.deltaY)).toEqual([-100, -100, -100, -100, -100])
    expect(sentWheelParams(debuggerManager).map(params => params.deltaX)).toEqual([0, 0, 0, 0, 0])
  })

  it('scroll right sends deltaX positive, deltaY=0', async () => {
    const debuggerManager = makeDebugger()
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    await handler(42, { direction: 'right', distance_px: 250, segments: 5 }, 5000)

    expect(sentWheelParams(debuggerManager).map(params => params.deltaX)).toEqual([50, 50, 50, 50, 50])
    expect(sentWheelParams(debuggerManager).map(params => params.deltaY)).toEqual([0, 0, 0, 0, 0])
  })

  it('scroll left sends deltaX negative, deltaY=0', async () => {
    const debuggerManager = makeDebugger()
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    await handler(42, { direction: 'left', distance_px: 250, segments: 5 }, 5000)

    expect(sentWheelParams(debuggerManager).map(params => params.deltaX)).toEqual([-50, -50, -50, -50, -50])
    expect(sentWheelParams(debuggerManager).map(params => params.deltaY)).toEqual([0, 0, 0, 0, 0])
  })

  it('default segments=5 when not specified', async () => {
    const debuggerManager = makeDebugger()
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    await handler(42, { direction: 'down', distance_px: 125 }, 5000)

    expect(debuggerManager.send).toHaveBeenCalledTimes(5)
    expect(sentWheelParams(debuggerManager).map(params => params.deltaY)).toEqual([25, 25, 25, 25, 25])
  })

  it('uses explicit wheel coordinates when provided', async () => {
    const debuggerManager = makeDebugger()
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    await handler(42, { direction: 'down', distance_px: 100, segments: 1, x: 980, y: 360 }, 5000)

    expect(sentWheelParams(debuggerManager)[0]).toMatchObject({
      x: 980,
      y: 360,
      deltaX: 0,
      deltaY: 100,
    })
  })

  it('uses injected clock + random for deterministic segment timing', async () => {
    vi.useFakeTimers()
    const debuggerManager = makeDebugger()
    const clock = vi.fn(() => 10_000)
    const random = vi.fn(() => 0.5)
    const handler = scrollHandler({
      debugger: debuggerManager as DebuggerManager,
      clock,
      random,
    })

    const pending = handler(42, { direction: 'down', distance_px: 300, segments: 3 }, 5000)
    await vi.advanceTimersByTimeAsync(76)
    await vi.advanceTimersByTimeAsync(76)
    const result = await pending

    expect(result.ok).toBe(true)
    expect(clock).toHaveBeenCalled()
    expect(random).toHaveBeenCalledTimes(4)
    expect(debuggerManager.send).toHaveBeenCalledTimes(3)
  })

  it('SESSION_DETACHED throws typed error', async () => {
    const debuggerManager = makeDebugger()
    vi.mocked(debuggerManager.send).mockRejectedValueOnce(new SessionDetachedError(42, 'target_closed'))
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    await expect(handler(42, { direction: 'down', distance_px: 500, segments: 5 }, 5000))
      .rejects.toMatchObject({
        name: 'ActionFailureError',
        code: 'SESSION_DETACHED',
        retryable: true,
      } satisfies Partial<ActionFailureError>)
  })

  it('segments=1 sends one large wheel event with full distance_px', async () => {
    const debuggerManager = makeDebugger()
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    await handler(42, { direction: 'down', distance_px: 500, segments: 1 }, 5000)

    expect(debuggerManager.send).toHaveBeenCalledTimes(1)
    expect(sentWheelParams(debuggerManager)[0]).toMatchObject({ deltaX: 0, deltaY: 500 })
  })

  it('rounds delta-per-segment to integers (CDP rejects float deltas on some Chrome versions)', async () => {
    const debuggerManager = makeDebugger()
    const handler = scrollHandler({ debugger: debuggerManager as DebuggerManager, segmentIntervalMs: () => 0 })

    await handler(42, { direction: 'down', distance_px: 7, segments: 3 }, 5000)

    const deltas = sentWheelParams(debuggerManager).map(params => params.deltaY)
    expect(deltas.every(delta => Number.isInteger(delta))).toBe(true)
    expect(deltas.reduce((sum, delta) => sum + delta, 0)).toBe(7)
  })
})
