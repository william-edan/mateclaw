import { afterEach, describe, expect, it, vi } from 'vitest'

import { ActionFailureError } from '../ActionExecutor'
import type { DebuggerManager } from '../../debugger-manager'
import { SessionDetachedError } from '../../debugger-manager'
import type { Point } from '../../../lib/windmouse'
import { moveMouseHandler, viewportCenterFromDebugger } from './move_mouse'

// 临时调试开关 DOM_ONLY_NO_CDP_FALLBACK 生产默认 true(move_mouse no-op,不走 CDP)。
// 本测试套件覆盖的是真实 CDP 鼠标移动行为,故在测试中关掉该开关。见 debug-flags.ts。
vi.mock('./debug-flags', () => ({ DOM_ONLY_NO_CDP_FALLBACK: false }))

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

interface CapturedSend {
  tabId: number
  method: string
  params: Record<string, unknown>
}

/**
 * Builds a fake DebuggerManager. Records every send() call so individual
 * tests can assert on the dispatched CDP events.
 *
 * `failOn(method, predicate)` lets a test inject a SessionDetachedError on
 * a specific CDP send (used by the SESSION_DETACHED mid-path test).
 */
function fakeDebugger(opts: {
  failAt?: (call: CapturedSend, index: number) => Error | null
} = {}) {
  const sent: CapturedSend[] = []
  const attachOrder: string[] = []

  const debuggerStub = {
    attach: vi.fn(async (_tabId: number) => {
      attachOrder.push('attach')
    }),
    detach: vi.fn(async (_tabId: number) => {
      attachOrder.push('detach')
    }),
    send: vi.fn(async (tabId: number, method: string, params: Record<string, unknown>) => {
      const call: CapturedSend = { tabId, method, params: { ...params } }
      const failure = opts.failAt?.(call, sent.length) ?? null
      sent.push(call)
      attachOrder.push('send')
      if (failure) throw failure
      return {} as Record<string, never>
    }),
    isAttached: vi.fn((_tabId: number) => true),
  } as unknown as DebuggerManager

  return { debuggerStub, sent, attachOrder }
}

/**
 * Deterministic linear-congruential RNG used by both the WindMouse library
 * (so the path is reproducible) and this test (so we can assert exact
 * sequences). Mirrors the helper in windmouse.test.ts.
 */
function seededRandom(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (s * 9301 + 49297) % 233280
    return s / 233280
  }
}

/** Build a recording sleep stub. */
function fakeSleep() {
  const durations: number[] = []
  const sleep = vi.fn(async (ms: number) => {
    durations.push(ms)
  })
  return { sleep, durations }
}

/** Fixed-tick clock (each call advances by `step` ms). */
function fixedClock(start: number, step = 0): () => number {
  let now = start
  return () => {
    const v = now
    now += step
    return v
  }
}

interface CursorMessage {
  tabId: number
  x: number
  y: number
}

/**
 * Fake chrome.tabs.sendMessage recorder for the visual phantom-cursor relay.
 *
 * `mode`:
 *   - 'resolve' (default): every send resolves (the happy path).
 *   - 'reject': every send returns a rejected promise — the handler must
 *     swallow it and still complete the real CDP path.
 *   - 'throw': sendMessage throws synchronously — same expectation.
 */
function fakeCursorChrome(mode: 'resolve' | 'reject' | 'throw' = 'resolve') {
  const cursors: CursorMessage[] = []
  const sendMessage = vi.fn((tabId: number, message: unknown) => {
    const m = message as { type?: string; x?: number; y?: number }
    if (m?.type === 'INDICATOR_CURSOR') {
      cursors.push({ tabId, x: m.x as number, y: m.y as number })
    }
    if (mode === 'throw') throw new Error('SW tearing down')
    if (mode === 'reject') return Promise.reject(new Error('no receiver'))
    return Promise.resolve({ ok: true })
  })
  const chrome = { tabs: { sendMessage } } as unknown as typeof globalThis.chrome
  return { chrome, sendMessage, cursors }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('move_mouse handler', () => {
  it('natural profile dispatches one mouseMoved per waypoint after the first (>= 4 dispatched events)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(1),
      clock: () => 1000,
      sleep,
    })

    const result = await handler(42, { x: 400, y: 300, profile: 'natural' }, 5000)

    expect(result.ok).toBe(true)
    // WindMouse natural profile guarantees >= 5 waypoints for distance > 10px.
    // The first waypoint = starting position, so dispatched events = N - 1 >= 4.
    expect(sent.length).toBeGreaterThanOrEqual(4)
    for (const call of sent) {
      expect(call.method).toBe('Input.dispatchMouseEvent')
    }
  })

  it('linear profile dispatches exactly 1 mouseMoved (destination only)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(1),
      clock: () => 0,
      sleep,
    })

    const result = await handler(42, { x: 500, y: 400, profile: 'linear' }, 5000)

    expect(result.ok).toBe(true)
    // Linear profile = 2 waypoints (from + to). Skipping the starting one
    // leaves exactly 1 dispatched mouseMoved at the destination.
    expect(sent).toHaveLength(1)
    expect(sent[0]!.params).toMatchObject({
      type: 'mouseMoved',
      x: 500,
      y: 400,
      button: 'none',
    })
  })

  it('first move starts from injected viewport center when cursorState is empty', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const cursorState = new Map<number, Point>()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(99),
      clock: () => 0,
      sleep,
      cursorState,
      initialCursorPosition: async () => ({ x: 320, y: 240 }),
      cursorEmitMinIntervalMs: 0,
    })

    await handler(99, { x: 50, y: 80, profile: 'natural' }, 5000)

    expect(sent.length).toBeGreaterThanOrEqual(4)
    // The first dispatched waypoint should be near the viewport center, not
    // the old top-left origin. This keeps the user-visible cursor from flying
    // diagonally across the whole page on first use.
    expect(sent[0]!.params.x as number).toBeGreaterThan(250)
    expect(sent[0]!.params.y as number).toBeGreaterThan(180)
    expect(cursorState.get(99)).toEqual({ x: 50, y: 80 })
  })

  it('first move falls back to {640,400} if initial cursor resolver fails', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(99),
      clock: () => 0,
      sleep,
      initialCursorPosition: async () => {
        throw new Error('viewport unavailable')
      },
    })

    await handler(99, { x: 50, y: 80, profile: 'natural' }, 5000)

    expect(sent[0]!.params.x as number).toBeGreaterThan(560)
    expect(sent[0]!.params.y as number).toBeGreaterThan(330)
  })

  it('subsequent move starts from previous arrival point (cursorState carries over)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const cursorState = new Map<number, Point>()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(7),
      clock: () => 0,
      sleep,
      cursorState,
    })

    await handler(11, { x: 100, y: 100, profile: 'linear' }, 5000)
    await handler(11, { x: 200, y: 100, profile: 'linear' }, 5000)

    // First call generated 1 dispatch (destination 100,100), second call
    // also generated 1 dispatch (destination 200,100). If the second call
    // had restarted from {0,0} the WindMouse path would still terminate
    // at {200,100} but cursorState.previous would be wrong — so the
    // strongest signal is that the linear-profile FROM was {100,100}, not
    // {0,0}. Linear profile encodes that via its "exactly 2 waypoints"
    // guarantee, the first being `from`. We assert by tracking that the
    // SECOND dispatch's coordinates are the second target and that
    // cursorState carries through.
    expect(sent).toHaveLength(2)
    expect(sent[0]!.params).toMatchObject({ x: 100, y: 100 })
    expect(sent[1]!.params).toMatchObject({ x: 200, y: 100 })
    expect(cursorState.get(11)).toEqual({ x: 200, y: 100 })
  })

  it('updates cursorState to params.{x,y} after success', async () => {
    const { debuggerStub } = fakeDebugger()
    const { sleep } = fakeSleep()
    const cursorState = new Map<number, Point>()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(3),
      clock: () => 0,
      sleep,
      cursorState,
    })

    await handler(7, { x: 321, y: 654, profile: 'natural' }, 5000)

    expect(cursorState.get(7)).toEqual({ x: 321, y: 654 })
  })

  it('all dispatched events use type=mouseMoved, button=none', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(13),
      clock: () => 0,
      sleep,
    })

    await handler(42, { x: 300, y: 300, profile: 'natural' }, 5000)

    expect(sent.length).toBeGreaterThan(0)
    for (const call of sent) {
      expect(call.params.type).toBe('mouseMoved')
      expect(call.params.button).toBe('none')
    }
  })

  it('uses injected random for deterministic waypoint sequence (same seed → same dispatched coordinates)', async () => {
    const run = async () => {
      const { debuggerStub, sent } = fakeDebugger()
      const { sleep } = fakeSleep()
      const handler = moveMouseHandler({
        debugger: debuggerStub,
        random: seededRandom(2026),
        clock: () => 0,
        sleep,
      })
      await handler(1, { x: 250, y: 150, profile: 'natural' }, 5000)
      return sent.map(c => ({ x: c.params.x, y: c.params.y }))
    }
    const a = await run()
    const b = await run()
    expect(a).toEqual(b)
    expect(a.length).toBeGreaterThanOrEqual(4)
  })

  it('uses injected sleep with monotone non-decreasing ms gaps that sum to roughly the path duration', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep, durations } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(5),
      clock: () => 0,
      sleep,
    })

    await handler(42, { x: 400, y: 300, profile: 'natural' }, 5000)

    // sleep called once per dispatched waypoint (N - 1 total).
    expect(durations.length).toBe(sent.length)
    // All gaps must be >= 0 (waypoint t values are monotone non-decreasing).
    for (const ms of durations) {
      expect(ms).toBeGreaterThanOrEqual(0)
    }
    // Sum equals exactly the final waypoint's t, which equals the
    // WindMouse durationMs (200..800 range for our 500px distance).
    const total = durations.reduce((s, ms) => s + ms, 0)
    expect(total).toBeGreaterThanOrEqual(200)
    expect(total).toBeLessThanOrEqual(800)
  })

  it('SESSION_DETACHED during a mid-path mouseMoved throws ActionFailureError', async () => {
    // Inject a detach failure on the THIRD send (index 2). Anything that
    // bubbles a SessionDetachedError out of debugger.send() must surface
    // as ActionFailureError code=SESSION_DETACHED.
    const { debuggerStub } = fakeDebugger({
      failAt: (_call, index) => {
        if (index === 2) return new SessionDetachedError(42, 'target_closed')
        return null
      },
    })
    const { sleep } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(17),
      clock: () => 0,
      sleep,
    })

    await expect(handler(42, { x: 400, y: 300, profile: 'natural' }, 5000))
      .rejects.toBeInstanceOf(ActionFailureError)

    // Run again to inspect the code/message — the rejection promise above
    // is consumed by expect; here we make a fresh attempt with the same
    // failure injection.
    const { debuggerStub: stub2 } = fakeDebugger({
      failAt: (_call, index) => {
        if (index === 2) return new SessionDetachedError(42, 'target_closed')
        return null
      },
    })
    const handler2 = moveMouseHandler({
      debugger: stub2,
      random: seededRandom(17),
      clock: () => 0,
      sleep,
    })
    try {
      await handler2(42, { x: 400, y: 300, profile: 'natural' }, 5000)
      throw new Error('handler should have thrown')
    } catch (err) {
      expect(err).toBeInstanceOf(ActionFailureError)
      const afe = err as ActionFailureError
      expect(afe.code).toBe('SESSION_DETACHED')
    }
  })

  it('returns Success with payload.waypoints = count of waypoints (including starting position)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(11),
      clock: () => 0,
      sleep,
    })

    const result = await handler(42, { x: 400, y: 300, profile: 'natural' }, 5000)

    expect(result.ok).toBe(true)
    if (result.ok === true) {
      // Total waypoint count = dispatched events + 1 (the starting point
      // we skipped because the cursor is already there).
      expect(result.payload.waypoints).toBe(sent.length + 1)
      expect(result.payload.waypoints as number).toBeGreaterThanOrEqual(5)
    }
  })

  it('returns Success with payload.arrived_at_ms = clock() at completion', async () => {
    const { debuggerStub } = fakeDebugger()
    const { sleep } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(11),
      clock: () => 1_700_000_000_000, // fixed
      sleep,
    })

    const result = await handler(42, { x: 100, y: 80, profile: 'linear' }, 5000)

    expect(result.ok).toBe(true)
    if (result.ok === true) {
      expect(result.payload.arrived_at_ms).toBe(1_700_000_000_000)
    }
  })

  it('per-tab cursorState isolation — two tabs maintain separate cursor positions', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const cursorState = new Map<number, Point>()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(99),
      clock: () => 0,
      sleep,
      cursorState,
    })

    await handler(1, { x: 100, y: 100, profile: 'linear' }, 5000)
    await handler(2, { x: 500, y: 400, profile: 'linear' }, 5000)
    await handler(1, { x: 150, y: 100, profile: 'linear' }, 5000)

    // Tab 1's final position should be {150,100} (NOT {500,400} from tab 2).
    expect(cursorState.get(1)).toEqual({ x: 150, y: 100 })
    expect(cursorState.get(2)).toEqual({ x: 500, y: 400 })

    // The dispatches: tab1 → {100,100}, tab2 → {500,400}, tab1 → {150,100}.
    // If cursors were shared the third call's PATH would start at
    // {500,400} (tab 2's last point), but linear-profile destination is
    // unaffected by start point — we already proved isolation via the
    // cursorState map. Just sanity-check destinations are correct.
    expect(sent[0]!.params).toMatchObject({ x: 100, y: 100 })
    expect(sent[1]!.params).toMatchObject({ x: 500, y: 400 })
    expect(sent[2]!.params).toMatchObject({ x: 150, y: 100 })
  })

  it('attaches debugger before any CDP send', async () => {
    const { debuggerStub, attachOrder } = fakeDebugger()
    const { sleep } = fakeSleep()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(33),
      clock: () => 0,
      sleep,
    })

    await handler(42, { x: 50, y: 50, profile: 'linear' }, 5000)

    expect(attachOrder[0]).toBe('attach')
    expect(attachOrder.slice(1).every(op => op === 'send' || op === 'detach')).toBe(true)
    expect(debuggerStub.attach).toHaveBeenCalledExactlyOnceWith(42)
  })

  // -------------------------------------------------------------------------
  // Visual phantom-cursor relay — the on-page overlay must GLIDE the same
  // WindMouse path the real CDP pointer takes (not teleport to the endpoint).
  // -------------------------------------------------------------------------

  it('relays INDICATOR_CURSOR to the same tab for the visual phantom cursor as it walks the path', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const { chrome, sendMessage, cursors } = fakeCursorChrome()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(5),
      clock: () => 0,
      sleep,
      chrome,
      // Relay every waypoint (no throttling) so the count lines up with the
      // dispatched CDP events exactly.
      cursorEmitMinIntervalMs: 0,
    })

    await handler(77, { x: 400, y: 300, profile: 'natural' }, 5000)

    // With throttling disabled, one INDICATOR_CURSOR per dispatched waypoint.
    expect(cursors.length).toBe(sent.length)
    expect(cursors.length).toBeGreaterThanOrEqual(4)
    // Every relay targets the action's tab and carries the matching waypoint
    // coordinate — i.e. the visual cursor traces the SAME points as the real one.
    for (let i = 0; i < cursors.length; i++) {
      expect(cursors[i]!.tabId).toBe(77)
      expect(cursors[i]!.x).toBe(sent[i]!.params.x)
      expect(cursors[i]!.y).toBe(sent[i]!.params.y)
    }
    // sendMessage was used for the relay (type === INDICATOR_CURSOR).
    expect(sendMessage).toHaveBeenCalled()
  })

  it('always relays the FINAL waypoint so the phantom lands exactly on target', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    // A large throttle window would normally suppress interior relays, but the
    // last waypoint must ALWAYS emit so the overlay ends on the click point.
    const { chrome, cursors } = fakeCursorChrome()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(5),
      clock: () => 0,
      sleep,
      chrome,
      cursorEmitMinIntervalMs: 10_000, // larger than any path duration
    })

    await handler(77, { x: 420, y: 360, profile: 'natural' }, 5000)

    const lastDispatch = sent[sent.length - 1]!
    const lastCursor = cursors[cursors.length - 1]!
    expect(lastCursor.x).toBe(420)
    expect(lastCursor.y).toBe(360)
    // The destination is exactly the requested target.
    expect(lastDispatch.params).toMatchObject({ x: 420, y: 360 })
    expect(lastCursor.x).toBe(lastDispatch.params.x)
    expect(lastCursor.y).toBe(lastDispatch.params.y)
  })

  it('throttles dense waypoints: fewer cursor relays than dispatched CDP moves, but never zero', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const { chrome, cursors } = fakeCursorChrome()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(5),
      clock: () => 0,
      sleep,
      chrome,
      // 60ms window over a ~200-800ms path → some interior waypoints coalesce.
      cursorEmitMinIntervalMs: 60,
    })

    await handler(77, { x: 600, y: 450, profile: 'natural' }, 5000)

    expect(cursors.length).toBeGreaterThan(0)
    // A 600x450 natural path emits ~200 CDP waypoints; the 60ms throttle must
    // coalesce them into far fewer relays (strictly fewer than dispatched).
    expect(cursors.length).toBeLessThan(sent.length)
    // Final waypoint still made it through.
    expect(cursors[cursors.length - 1]!).toMatchObject({ x: 600, y: 450 })
  })

  it('linear profile relays exactly one INDICATOR_CURSOR (the destination)', async () => {
    const { debuggerStub } = fakeDebugger()
    const { sleep } = fakeSleep()
    const { chrome, cursors } = fakeCursorChrome()
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(1),
      clock: () => 0,
      sleep,
      chrome,
    })

    await handler(5, { x: 500, y: 400, profile: 'linear' }, 5000)

    expect(cursors).toEqual([{ tabId: 5, x: 500, y: 400 }])
  })

  it('a rejecting sendMessage never breaks the real CDP path', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const { chrome } = fakeCursorChrome('reject')
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(5),
      clock: () => 0,
      sleep,
      chrome,
      cursorEmitMinIntervalMs: 0,
    })

    const result = await handler(77, { x: 400, y: 300, profile: 'natural' }, 5000)

    expect(result.ok).toBe(true)
    // All CDP mouseMoved events still dispatched despite every relay rejecting.
    expect(sent.length).toBeGreaterThanOrEqual(4)
    for (const call of sent) expect(call.params.type).toBe('mouseMoved')
  })

  it('a synchronously-throwing sendMessage never breaks the real CDP path', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    const { chrome } = fakeCursorChrome('throw')
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(5),
      clock: () => 0,
      sleep,
      chrome,
      cursorEmitMinIntervalMs: 0,
    })

    const result = await handler(77, { x: 400, y: 300, profile: 'natural' }, 5000)

    expect(result.ok).toBe(true)
    expect(sent.length).toBeGreaterThanOrEqual(4)
  })

  it('omitting chrome (and with no global) skips the relay but still dispatches CDP moves', async () => {
    // Pin globalThis.chrome to undefined so this assertion is independent of
    // test ordering (another suite could leak a global chrome stub).
    vi.stubGlobal('chrome', undefined)
    const { debuggerStub, sent } = fakeDebugger()
    const { sleep } = fakeSleep()
    // No `chrome` dep + no global chrome → relay is a silent no-op; the CDP
    // path must be unaffected.
    const handler = moveMouseHandler({
      debugger: debuggerStub,
      random: seededRandom(5),
      clock: () => 0,
      sleep,
    })

    const result = await handler(77, { x: 400, y: 300, profile: 'natural' }, 5000)

    expect(result.ok).toBe(true)
    expect(sent.length).toBeGreaterThanOrEqual(4)
  })

  it('viewportCenterFromDebugger reads the page viewport center via Runtime.evaluate', async () => {
    const { debuggerStub } = fakeDebugger()
    vi.spyOn(debuggerStub, 'send').mockResolvedValueOnce({
      result: { type: 'object', value: { x: 390, y: 422 } },
    } as any)

    const point = await viewportCenterFromDebugger(debuggerStub, 77)

    expect(point).toEqual({ x: 390, y: 422 })
    expect(debuggerStub.send).toHaveBeenCalledWith(
      77,
      'Runtime.evaluate',
      expect.objectContaining({ returnByValue: true }),
    )
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})
