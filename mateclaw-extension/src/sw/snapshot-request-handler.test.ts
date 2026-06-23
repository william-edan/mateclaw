import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SnapshotRequestHandler, settleArgs, waitForSettle } from './snapshot-request-handler'
import { EdgeMessageKind, type EdgeMessage } from '../shared/edge-protocol'
import type { TabRefResolver } from './action/tab-ref-resolver'
import type { TabRef } from './action/types'

function fakeChrome() {
  return {
    chrome: {
      scripting: {
        executeScript: vi.fn(async () => [{
          result: {
            tree: 'Button[ref=ref_1]: Submit',
            viewport: { w: 1280, h: 800 },
          },
          frameId: 0,
        }]),
      },
    } as unknown as typeof globalThis.chrome,
  }
}

function fakeResolver(resolveImpl: (tabRef: TabRef) => Promise<number | null> = async () => 42) {
  return {
    resolve: vi.fn(resolveImpl),
  } as unknown as TabRefResolver
}

function snapshotRequest(
  payload: Record<string, unknown> = {},
  overrides: Partial<EdgeMessage> = {},
): EdgeMessage {
  return {
    v: 1,
    msg_id: 'request-1',
    kind: EdgeMessageKind.A11ySnapshotRequest,
    ts: 0,
    trace_id: 'trace-1',
    session_id: 'must-not-propagate',
    payload: {
      tab_ref: 'main',
      filter: 'interactive',
      depth: 15,
      max_chars: 200000,
      ...payload,
    },
    ...overrides,
  }
}

function makeHandler(opts: {
  resolver?: TabRefResolver
  chrome?: typeof globalThis.chrome
  uuid?: () => string
  clock?: () => number
} = {}) {
  const sentUp: EdgeMessage[] = []
  const { chrome } = fakeChrome()
  const resolver = opts.resolver ?? fakeResolver()
  const handler = new SnapshotRequestHandler({
    resolver,
    chrome: opts.chrome ?? chrome,
    sendUp: msg => sentUp.push(msg),
    uuid: opts.uuid ?? (() => 'snap-1'),
    clock: opts.clock ?? (() => 1730000000123),
  })
  return { handler, sentUp, chrome: opts.chrome ?? chrome, resolver }
}

describe('SnapshotRequestHandler', () => {
  it('happy path: invokes scripting.executeScript with correct args + responds with tree+viewport', async () => {
    const { handler, sentUp, chrome } = makeHandler()

    await handler.handle(snapshotRequest())

    // Two calls now: (1) inject the idempotent a11y content script so the
    // extractor is guaranteed present, (2) run the extraction func.
    expect(chrome.scripting.executeScript).toHaveBeenCalledTimes(2)
    expect(chrome.scripting.executeScript).toHaveBeenNthCalledWith(1, expect.objectContaining({
      target: { tabId: 42, allFrames: false },
      files: ['content/a11y-tree.js'],
    }))
    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42, allFrames: false },
      // settleQuietMs(200) + settleCapMs(2000) appended after the original 5 args.
      args: ['interactive', 15, 200000, null, null, 200, 2000],
    }))
    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.kind).toBe(EdgeMessageKind.A11ySnapshotResponse)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'snap-1',
      captured_at_ms: 1730000000123,
      tab_ref: 42,
      tree: 'Button[ref=ref_1]: Submit',
      viewport: { w: 1280, h: 800 },
    })
  })

  it('uses injected uuid factory for snapshot_id', async () => {
    const { handler, sentUp } = makeHandler({ uuid: () => 'snap-from-test' })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.snapshot_id).toBe('snap-from-test')
  })

  it('uses injected clock for captured_at_ms', async () => {
    const { handler, sentUp } = makeHandler({ clock: () => 999 })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.captured_at_ms).toBe(999)
  })

  it('tab_ref="main" resolves via TabRefResolver before invoking scripting', async () => {
    const resolver = fakeResolver(async tabRef => tabRef === 'main' ? 77 : null)
    const { handler, chrome } = makeHandler({ resolver })

    await handler.handle(snapshotRequest({ tab_ref: 'main' }))

    expect(resolver.resolve).toHaveBeenCalledExactlyOnceWith('main')
    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 77, allFrames: false },
    }))
  })

  it('tab_ref=42 (integer) resolves to 42 verbatim', async () => {
    const resolver = fakeResolver(async tabRef => typeof tabRef === 'number' ? tabRef : null)
    const { handler, chrome, sentUp } = makeHandler({ resolver })

    await handler.handle(snapshotRequest({ tab_ref: 42 }))

    expect(resolver.resolve).toHaveBeenCalledExactlyOnceWith(42)
    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42, allFrames: false },
    }))
    expect(sentUp[0]!.payload!.tab_ref).toBe(42)
  })

  it('unresolvable tab_ref -> response with error.code=NO_TARGET_TAB, no executeScript call', async () => {
    const resolver = fakeResolver(async () => null)
    const { handler, chrome, sentUp } = makeHandler({ resolver })

    await handler.handle(snapshotRequest({ tab_ref: 'active' }))

    expect(chrome.scripting.executeScript).not.toHaveBeenCalled()
    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'snap-1',
      captured_at_ms: 1730000000123,
      tab_ref: -1,
      tree: '',
      viewport: { w: 0, h: 0 },
      error: {
        code: 'NO_TARGET_TAB',
        retryable: false,
      },
    })
  })

  it('chrome.scripting.executeScript rejection -> response with error.code=SNAPSHOT_FAILED', async () => {
    const { chrome } = fakeChrome()
    vi.mocked(chrome.scripting.executeScript).mockRejectedValue(new Error('tab navigated'))
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(snapshotRequest())

    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      snapshot_id: 'snap-1',
      captured_at_ms: 1730000000123,
      tab_ref: 42,
      tree: '',
      viewport: { w: 0, h: 0 },
      error: {
        code: 'SNAPSHOT_FAILED',
        retryable: true,
      },
    })
    expect(String((sentUp[0]!.payload!.error as Record<string, unknown>).message)).toContain('tab navigated')
  })

  it('in_reply_to preserved from request msg_id', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(snapshotRequest({}, { msg_id: 'request-xyz' }))

    expect(sentUp[0]!.in_reply_to).toBe('request-xyz')
  })

  it('trace_id propagated from request', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(snapshotRequest({}, { trace_id: 'trace-xyz' }))

    expect(sentUp[0]!.trace_id).toBe('trace-xyz')
  })

  it('session_id="" on outbound (P0-1 invariant)', async () => {
    const { handler, sentUp } = makeHandler()

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.session_id).toBe('')
  })

  it('passes filter/depth/max_chars/ref_id as args to executeScript', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest({
      filter: 'interactive',
      depth: 10,
      max_chars: 50000,
      ref_id: 'ref_3',
    }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      args: ['interactive', 10, 50000, 'ref_3', null, 200, 2000],
    }))
  })

  it('omits ref_id when not provided in request', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest({
      filter: 'interactive',
      depth: 10,
      max_chars: 50000,
    }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      args: ['interactive', 10, 50000, null, null, 200, 2000],
    }))
  })

  it('defaults missing depth/max_chars for legacy snapshot requests', async () => {
    const { handler, chrome, sentUp } = makeHandler()

    await handler.handle(snapshotRequest({
      depth: undefined,
      max_chars: undefined,
    }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      args: ['interactive', 15, 200000, null, null, 200, 2000],
    }))
    expect(sentUp[0]!.payload).toMatchObject({
      tree: 'Button[ref=ref_1]: Submit',
      viewport: { w: 1280, h: 800 },
    })
  })

  it('uses target.frameIds when frame_id is explicitly provided', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest({ frame_id: 7 }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42, frameIds: [7] },
      args: ['interactive', 15, 200000, null, 7, 200, 2000],
    }))
  })

  it('treats frame_id=0 as an explicit top-frame frameIds request', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest({ frame_id: 0 }))

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(expect.objectContaining({
      target: { tabId: 42, frameIds: [0] },
      args: ['interactive', 15, 200000, null, 0, 200, 2000],
    }))
  })

  it('malformed frame_id -> response with SNAPSHOT_FAILED and no executeScript call', async () => {
    const { handler, chrome, sentUp } = makeHandler()

    await handler.handle(snapshotRequest({ frame_id: '7' }))

    expect(chrome.scripting.executeScript).not.toHaveBeenCalled()
    expect(sentUp[0]!.payload).toMatchObject({
      tree: '',
      viewport: { w: 0, h: 0 },
      error: {
        code: 'SNAPSHOT_FAILED',
        retryable: false,
      },
    })
  })

  it('tree string is passed through verbatim from injection result', async () => {
    const { chrome } = fakeChrome()
    ;(chrome.scripting.executeScript as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([({
      result: {
        tree: 'Button[ref=ref_1]: Submit\n  Link[ref=ref_2]: Learn more',
        viewport: { w: 1280, h: 800 },
      },
      frameId: 0,
    }) as unknown as chrome.scripting.InjectionResult<unknown>])
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.tree).toBe('Button[ref=ref_1]: Submit\n  Link[ref=ref_2]: Learn more')
  })

  it('viewport {w,h} from injection result is included in response', async () => {
    const { chrome } = fakeChrome()
    ;(chrome.scripting.executeScript as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([({
      result: {
        tree: 'Button[ref=ref_1]: Submit',
        viewport: { w: 390, h: 844 },
      },
      frameId: 0,
    }) as unknown as chrome.scripting.InjectionResult<unknown>])
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.viewport).toEqual({ w: 390, h: 844 })
  })

  // ---------------------------------------------------------------------
  // DOM-settle regression coverage. The settle wait runs INSIDE the real
  // injected func (mocked away here), so these tests assert the wiring
  // around it: response shape is unchanged, and empty trees still pass
  // through empty (the server caches blank trees as STALE — must not be
  // defeated by adding a settle step). waitForSettle's own timing logic is
  // unit-tested separately below with fake timers.
  // ---------------------------------------------------------------------

  it('settle wiring: response still carries url/title/tree/viewport unchanged', async () => {
    const { chrome } = fakeChrome()
    ;(chrome.scripting.executeScript as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([({
      result: {
        tree: 'Button[ref=ref_1]: Submit',
        viewport: { w: 1024, h: 768 },
        url: 'https://www.douyin.com/search/cat',
        title: '猫 - 抖音搜索',
      },
      frameId: 0,
    }) as unknown as chrome.scripting.InjectionResult<unknown>])
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload).toMatchObject({
      tree: 'Button[ref=ref_1]: Submit',
      viewport: { w: 1024, h: 768 },
      url: 'https://www.douyin.com/search/cat',
      title: '猫 - 抖音搜索',
    })
  })

  it('settle does not change empty-tree behavior: empty tree stays "" (server STALE cache intact)', async () => {
    const { chrome } = fakeChrome()
    ;(chrome.scripting.executeScript as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([({
      result: {
        tree: '',
        viewport: { w: 1280, h: 800 },
        url: 'https://example.com',
        title: 'Example',
      },
      frameId: 0,
    }) as unknown as chrome.scripting.InjectionResult<unknown>])
    const { handler, sentUp } = makeHandler({ chrome })

    await handler.handle(snapshotRequest())

    // Empty tree is propagated verbatim — NOT turned into a SNAPSHOT_FAILED
    // error and NOT backfilled with anything. The settle step is orthogonal.
    expect(sentUp[0]!.payload!.tree).toBe('')
    expect(sentUp[0]!.payload!.error).toBeUndefined()
    expect(sentUp[0]!.payload).toMatchObject({
      url: 'https://example.com',
      title: 'Example',
      viewport: { w: 1280, h: 800 },
    })
  })

  it('settle timings default to 200/2000 in the extractor args', async () => {
    const { handler, chrome } = makeHandler()

    await handler.handle(snapshotRequest())

    const extractionCall = (chrome.scripting.executeScript as unknown as ReturnType<typeof vi.fn>)
      .mock.calls.find(c => Array.isArray((c[0] as { args?: unknown[] }).args))?.[0] as { args: unknown[] }
    expect(extractionCall.args.slice(-2)).toEqual([200, 2000])
  })
})

// ---------------------------------------------------------------------------
// CDP-first / JS-fallback path. When a DebuggerManager is wired, captureSnapshot
// tries the CDP-native a11y extractor first and falls back to the injected-JS
// walker on ANY failure. These tests stub the manager's CDP send + a metadata
// executeScript (settle + url/title/viewport).
// ---------------------------------------------------------------------------

interface CdpFake {
  /** Accessibility.getFullAXTree result, or an Error to throw. */
  axTree?: { nodes: Array<Record<string, unknown>> } | Error
  /** backendDOMNodeId → box model content quad. */
  box?: (backendNodeId: number) => number[] | undefined
}

/** Fake DebuggerManager whose send() routes the two CDP methods the extractor uses. */
function fakeDebuggerManager(fake: CdpFake) {
  const send = vi.fn(async (_tabId: number, method: string, params: unknown) => {
    if (method === 'Accessibility.getFullAXTree') {
      if (fake.axTree instanceof Error) throw fake.axTree
      return fake.axTree ?? { nodes: [] }
    }
    if (method === 'DOM.getBoxModel') {
      const id = (params as { backendNodeId?: number }).backendNodeId
      const content = id != null ? fake.box?.(id) : undefined
      if (!content) throw new Error('no box')
      return { model: { content, padding: content, border: content, margin: content, width: 1, height: 1 } }
    }
    throw new Error(`unexpected method ${method}`)
  })
  const attach = vi.fn(async () => {})
  return {
    manager: { attach, send, detach: vi.fn(async () => {}), scheduleIdleDetach: vi.fn(), isAttached: () => true } as unknown as
      import('./debugger-manager').DebuggerManager,
    send,
    attach,
  }
}

/** Chrome stub whose executeScript returns ONLY the CDP metadata shape. */
function metaChrome(meta = { viewport: { w: 1024, h: 768 }, url: 'https://example.com/a', title: 'A Page' }) {
  return {
    scripting: {
      executeScript: vi.fn(async () => [{ result: meta, frameId: 0 }]),
    },
  } as unknown as typeof globalThis.chrome
}

describe('SnapshotRequestHandler — CDP-first path', () => {
  function makeCdpHandler(opts: {
    cdp: CdpFake
    chrome?: typeof globalThis.chrome
    resolver?: TabRefResolver
  }) {
    const sentUp: EdgeMessage[] = []
    const { manager, send, attach } = fakeDebuggerManager(opts.cdp)
    const chrome = opts.chrome ?? metaChrome()
    const handler = new SnapshotRequestHandler({
      resolver: opts.resolver ?? fakeResolver(),
      chrome,
      sendUp: msg => sentUp.push(msg),
      uuid: () => 'snap-cdp',
      clock: () => 1730000000999,
      debuggerManager: manager,
    })
    return { handler, sentUp, chrome, send, attach }
  }

  it('uses the CDP extractor on success: tree from AX tree, metadata from injected meta script', async () => {
    const { handler, sentUp, send, attach, chrome } = makeCdpHandler({
      cdp: {
        axTree: {
          nodes: [
            { nodeId: '1', role: { value: 'RootWebArea' }, name: { value: 'Doc' }, childIds: ['2'], backendDOMNodeId: 1 },
            { nodeId: '2', role: { value: 'button' }, name: { value: 'Submit' }, backendDOMNodeId: 2 },
          ],
        },
        box: id => (id === 2 ? [120, 340, 200, 340, 200, 372, 120, 372] : undefined),
      },
    })

    await handler.handle(snapshotRequest())

    expect(attach).toHaveBeenCalledExactlyOnceWith(42)
    // getFullAXTree issued exactly once.
    expect(send.mock.calls.filter(c => c[1] === 'Accessibility.getFullAXTree')).toHaveLength(1)
    // Metadata script injected once (settle + url/title/viewport).
    expect(chrome.scripting.executeScript).toHaveBeenCalledTimes(1)
    expect(sentUp).toHaveLength(1)
    expect(sentUp[0]!.payload).toMatchObject({
      tree: 'Button[ref=ref_1, frame=0]: Submit @{120,340 80x32}',
      viewport: { w: 1024, h: 768 },
      url: 'https://example.com/a',
      title: 'A Page',
    })
  })

  it('falls back to the injected-JS walker when getFullAXTree rejects', async () => {
    // The metadata/extractor chrome stub here returns a full JS-walker result,
    // so the fallback path produces a tree from executeScript.
    const jsChrome = {
      scripting: {
        executeScript: vi.fn(async () => [{
          result: { tree: 'Link[ref=ref_1, frame=0]: JS fallback @{0,0 10x10}', viewport: { w: 800, h: 600 }, url: 'https://fallback', title: 'FB' },
          frameId: 0,
        }]),
      },
    } as unknown as typeof globalThis.chrome
    const { handler, sentUp } = makeCdpHandler({
      cdp: { axTree: new Error('CDP not supported') },
      chrome: jsChrome,
    })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload).toMatchObject({
      tree: 'Link[ref=ref_1, frame=0]: JS fallback @{0,0 10x10}',
      viewport: { w: 800, h: 600 },
      url: 'https://fallback',
      title: 'FB',
    })
    expect(sentUp[0]!.payload!.error).toBeUndefined()
  })

  it('falls back when the CDP tree is empty (zero emittable nodes)', async () => {
    const jsChrome = {
      scripting: {
        executeScript: vi.fn(async () => [{
          result: { tree: 'Button[ref=ref_1, frame=0]: From JS @{1,2 3x4}', viewport: { w: 640, h: 480 }, url: 'u', title: 't' },
          frameId: 0,
        }]),
      },
    } as unknown as typeof globalThis.chrome
    const { handler, sentUp } = makeCdpHandler({
      cdp: { axTree: { nodes: [] } }, // empty AX tree → extractor throws → fallback
      chrome: jsChrome,
    })

    await handler.handle(snapshotRequest())

    expect(sentUp[0]!.payload!.tree).toBe('Button[ref=ref_1, frame=0]: From JS @{1,2 3x4}')
  })

  it('explicit frame_id bypasses CDP and goes straight to the JS walker', async () => {
    const jsChrome = {
      scripting: {
        executeScript: vi.fn(async () => [{
          result: { tree: 'Button[ref=ref_1, frame=7]: framed @{0,0 5x5}', viewport: { w: 100, h: 100 }, url: 'f', title: 'f' },
          frameId: 7,
        }]),
      },
    } as unknown as typeof globalThis.chrome
    const { handler, sentUp, send } = makeCdpHandler({
      cdp: { axTree: { nodes: [{ nodeId: '1', role: { value: 'button' }, name: { value: 'x' }, backendDOMNodeId: 1 }] } },
      chrome: jsChrome,
    })

    await handler.handle(snapshotRequest({ frame_id: 7 }))

    // No CDP send for an explicit child-frame request.
    expect(send).not.toHaveBeenCalled()
    expect(sentUp[0]!.payload!.tree).toBe('Button[ref=ref_1, frame=7]: framed @{0,0 5x5}')
  })
})

describe('settleArgs', () => {
  it('defaults to [200, 2000] when no overrides supplied', () => {
    expect(settleArgs()).toEqual([200, 2000])
    expect(settleArgs({})).toEqual([200, 2000])
  })

  it('passes through valid non-negative finite overrides', () => {
    expect(settleArgs({ quietMs: 10, capMs: 50 })).toEqual([10, 50])
    expect(settleArgs({ quietMs: 0, capMs: 0 })).toEqual([0, 0])
  })

  it('clamps bogus values (negative / NaN / Infinity / non-number) to defaults', () => {
    expect(settleArgs({ quietMs: -5, capMs: -1 })).toEqual([200, 2000])
    expect(settleArgs({ quietMs: NaN, capMs: Infinity })).toEqual([200, 2000])
    expect(settleArgs({ quietMs: '10' as unknown as number })).toEqual([200, 2000])
  })
})

describe('waitForSettle', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    document.documentElement.innerHTML = ''
  })

  function setReadyState(state: DocumentReadyState) {
    Object.defineProperty(document, 'readyState', {
      configurable: true,
      get: () => state,
    })
  }

  // Real-timer sleep for the two tests that drive the real MutationObserver.
  // happy-dom delivers MutationObserver callbacks on a microtask, which
  // vi.advanceTimersByTimeAsync() does NOT deterministically order against a
  // DOM mutation — so the observer's quiet-timer re-arm sometimes hadn't run
  // when the test advanced time, intermittently letting waitForSettle resolve
  // early. Those tests run on REAL timers instead: a real macrotask wait yields
  // the event loop, so the observer callback (and its re-arm) is always
  // delivered before the next assertion. setTimeout only ever fires late, never
  // early, so we use generous windows + a "keep mutating → stays unsettled;
  // stop → settles" shape that is robust to scheduler jitter while still
  // catching an early-resolve or an arm-during-loading regression.
  const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

  it('readyState=complete + quiet window elapses -> resolves after quietMs', async () => {
    setReadyState('complete')
    const settled = vi.fn()
    const p = waitForSettle(document, { quietMs: 200, capMs: 2000 }).then(settled)

    // Not yet: quiet window hasn't fully elapsed.
    await vi.advanceTimersByTimeAsync(199)
    expect(settled).not.toHaveBeenCalled()

    // Cross the 200ms quiet boundary -> resolves (well before the 2000ms cap).
    await vi.advanceTimersByTimeAsync(1)
    await p
    expect(settled).toHaveBeenCalledOnce()
  })

  it('a DOM mutation resets the quiet timer (does not resolve early)', async () => {
    // Real timers + the real MutationObserver (see `sleep` note above): fake
    // timers cannot deterministically interleave happy-dom's microtask MO
    // delivery with a DOM mutation, which made this test flaky.
    vi.useRealTimers()
    setReadyState('complete')
    const settled = vi.fn()
    const p = waitForSettle(document, { quietMs: 50, capMs: 5000 }).then(settled)

    // Mutate the DOM repeatedly at an interval SHORTER than the 50ms quiet
    // window, for a total span well past a single window (~140ms). Each
    // mutation must re-arm a fresh quiet window, so the promise must NOT
    // resolve while the churn continues. If a mutation failed to reset the
    // timer, the very first 50ms gap would let it resolve and trip an
    // assertion below — that is the regression this guards against. The 5000ms
    // cap is far away and cannot be what (mis)resolves it here.
    for (let i = 0; i < 7; i++) {
      document.documentElement.appendChild(document.createElement('div'))
      await sleep(20)
      expect(settled).not.toHaveBeenCalled()
    }

    // Now stop mutating and let a full quiet window elapse (with margin). With
    // no more mutations the re-armed timer finally fires and it resolves.
    await sleep(120)
    await p
    expect(settled).toHaveBeenCalledOnce()
  })

  it('hard cap fires while still loading (readyState never reaches complete)', async () => {
    setReadyState('loading')
    const settled = vi.fn()
    const p = waitForSettle(document, { quietMs: 200, capMs: 1000 }).then(settled)

    // Quiet timer never arms while loading; only the cap can resolve it.
    await vi.advanceTimersByTimeAsync(999)
    expect(settled).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    await p
    expect(settled).toHaveBeenCalledOnce()
  })

  it('cap wins over quiet when cap < quiet', async () => {
    setReadyState('complete')
    const settled = vi.fn()
    const p = waitForSettle(document, { quietMs: 5000, capMs: 300 }).then(settled)

    await vi.advanceTimersByTimeAsync(300)
    await p
    expect(settled).toHaveBeenCalledOnce()
  })

  it('mutations during loading do NOT arm the quiet timer; resolution waits for load + quiet', async () => {
    // Real timers + the real MutationObserver (see `sleep` note above).
    vi.useRealTimers()
    setReadyState('loading')
    const settled = vi.fn()
    const p = waitForSettle(document, { quietMs: 50, capMs: 10000 }).then(settled)

    // Churn the DOM while still loading — quiet must stay disarmed.
    for (let i = 0; i < 5; i++) {
      document.documentElement.appendChild(document.createElement('span'))
      await sleep(20)
    }
    // Crucial: stop mutating and let a FULL quiet window elapse while STILL
    // loading. If the impl wrongly armed quiet on those mutations, it now has
    // an uninterrupted window to fire in and would resolve — this assertion is
    // what catches that regression. The 10000ms cap is far away and cannot be
    // what resolves it here, so a resolution now could only mean quiet armed
    // during loading. It must stay unsettled.
    await sleep(120)
    expect(settled).not.toHaveBeenCalled()

    // Transition to complete and fire window load -> quiet countdown begins.
    setReadyState('complete')
    window.dispatchEvent(new Event('load'))
    await sleep(120)
    await p
    expect(settled).toHaveBeenCalledOnce()
  })

  it('resolves immediately when there is no documentElement to observe', async () => {
    const fakeDoc = { documentElement: null, readyState: 'complete' } as unknown as Document
    const settled = vi.fn()
    const p = waitForSettle(fakeDoc).then(settled)
    await p
    expect(settled).toHaveBeenCalledOnce()
  })
})
