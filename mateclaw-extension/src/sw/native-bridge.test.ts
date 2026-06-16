import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { NativeBridge } from './native-bridge'
import { EdgeMessageKind, makeEdgeMessage } from '../shared/edge-protocol'

const HOST = 'com.mateclaw.browser_bridge'

// Controllable mock native Port. Tests drive inbound messages + disconnect
// manually and inspect what was posted. Every connectNative call creates a new
// instance so reconnect can be observed.
class MockPort {
  static instances: MockPort[] = []

  posted: unknown[] = []
  disconnectCalled = false

  private messageListeners: Array<(raw: unknown) => void> = []
  private disconnectListeners: Array<() => void> = []

  readonly onMessage = {
    addListener: (cb: (raw: unknown) => void) => this.messageListeners.push(cb),
  }
  readonly onDisconnect = {
    addListener: (cb: () => void) => this.disconnectListeners.push(cb),
  }

  constructor() {
    MockPort.instances.push(this)
  }

  postMessage(m: unknown): void {
    this.posted.push(m)
  }

  disconnect(): void {
    this.disconnectCalled = true
  }

  // --- test drivers ---
  fireMessage(raw: unknown): void {
    this.messageListeners.forEach(cb => cb(raw))
  }

  fireDisconnect(): void {
    this.disconnectListeners.forEach(cb => cb())
  }

  static last(): MockPort {
    const p = MockPort.instances[MockPort.instances.length - 1]
    if (!p) throw new Error('no MockPort instance')
    return p
  }
}

// connectNative shim: returns a fresh MockPort and respects a queued lastError.
let nextLastError: { message: string } | undefined

function installChrome() {
  const runtime = {
    connectNative: vi.fn((_host: string) => {
      // Native messaging reports failures via lastError; clear it after the
      // call that observes it, mirroring chrome's one-shot semantics.
      return new MockPort()
    }),
    get lastError() {
      return nextLastError
    },
  }
  ;(globalThis as unknown as Record<string, unknown>).chrome = { runtime }
  return runtime
}

function newBridge(resetIdleTimer = vi.fn()) {
  return { bridge: new NativeBridge(HOST, { resetIdleTimer }), resetIdleTimer }
}

describe('NativeBridge', () => {
  beforeEach(() => {
    MockPort.instances = []
    nextLastError = undefined
    installChrome()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('connects to the named native host (com.mateclaw.browser_bridge)', () => {
    const runtime = installChrome()
    new NativeBridge(HOST).connect()
    expect(runtime.connectNative).toHaveBeenCalledWith(HOST)
  })

  it('forwards messages via postMessage', () => {
    const { bridge } = newBridge()
    bridge.connect()
    const m = makeEdgeMessage({ kind: EdgeMessageKind.Ping, payload: { echo: 'x' } })
    bridge.send(m)
    expect(MockPort.last().posted).toContainEqual(m)
  })

  it('delivers inbound messages to listeners and unsubscribe stops delivery', () => {
    const { bridge } = newBridge()
    bridge.connect()
    const cb = vi.fn()
    const unsub = bridge.onMessage(cb)

    MockPort.last().fireMessage({
      v: 1, msg_id: 'x', kind: 'pong', ts: 0, trace_id: 't', session_id: 's', payload: {},
    })
    expect(cb).toHaveBeenCalled()
    expect(cb.mock.calls[0]?.[0]?.kind).toBe('pong')

    unsub()
    cb.mockClear()
    MockPort.last().fireMessage({
      v: 1, msg_id: 'x2', kind: 'pong', ts: 0, trace_id: 't', session_id: 's',
    })
    expect(cb).not.toHaveBeenCalled()
  })

  it('parses inbound objects (not just strings) coming off the port', () => {
    const { bridge } = newBridge()
    bridge.connect()
    const cb = vi.fn()
    bridge.onMessage(cb)
    // Native messaging delivers parsed JSON objects, not strings.
    MockPort.last().fireMessage({
      v: 1, msg_id: 'o', kind: 'heartbeat.ack', ts: 0, trace_id: 't', session_id: 's',
    })
    expect(cb.mock.calls[0]?.[0]?.kind).toBe('heartbeat.ack')
  })

  it('send() throws when not connected', () => {
    const { bridge } = newBridge()
    expect(() => bridge.send(makeEdgeMessage({ kind: EdgeMessageKind.Ping }))).toThrow(
      /not connected/,
    )
  })

  it('`connected` is true after connect and false after the port disconnects', () => {
    const { bridge } = newBridge()
    expect(bridge.connected).toBe(false)
    bridge.connect()
    expect(bridge.connected).toBe(true)
    MockPort.last().fireDisconnect()
    expect(bridge.connected).toBe(false)
  })

  it('disconnect() calls port.disconnect, clears connected, and suppresses reconnect', () => {
    vi.useFakeTimers()
    const { bridge } = newBridge()
    bridge.connect()
    const port = MockPort.last()

    bridge.disconnect()
    expect(port.disconnectCalled).toBe(true)
    expect(bridge.connected).toBe(false)

    // Advancing past any backoff window creates no new port.
    vi.advanceTimersByTime(60_000)
    expect(MockPort.instances.length).toBe(1)
  })

  it('onDisconnect callback fires when the port drops', () => {
    vi.useFakeTimers()
    const { bridge } = newBridge()
    bridge.connect()
    const cb = vi.fn()
    bridge.onDisconnect(cb)
    MockPort.last().fireDisconnect()
    expect(cb).toHaveBeenCalledTimes(1)
  })

  it('reconnects with exponential backoff while the host keeps failing', () => {
    vi.useFakeTimers()
    const { bridge } = newBridge()
    bridge.connect() // first port opens OK (no lastError)
    expect(MockPort.instances.length).toBe(1)

    // From here every (re)connect FAILS via lastError, so the attempt counter
    // grows and the backoff widens 1s → 2s → 4s. (connectNative still mints a
    // MockPort, but the bridge discards it on lastError and schedules retry.)
    nextLastError = { message: 'host down' }

    // Unexpected disconnect → first retry after 1s (attempt 0).
    MockPort.last().fireDisconnect()
    vi.advanceTimersByTime(1_000)
    expect(MockPort.instances.length).toBe(2) // retry fired (and failed)

    // Failed retry → next retry after 2s (attempt 1).
    vi.advanceTimersByTime(1_000)
    expect(MockPort.instances.length).toBe(2) // not yet
    vi.advanceTimersByTime(1_000)
    expect(MockPort.instances.length).toBe(3)

    // Failed retry → next retry after 4s (attempt 2).
    vi.advanceTimersByTime(3_000)
    expect(MockPort.instances.length).toBe(3) // not yet
    vi.advanceTimersByTime(1_000)
    expect(MockPort.instances.length).toBe(4)
  })

  it('resets backoff after a successful (re)connect', () => {
    vi.useFakeTimers()
    const { bridge } = newBridge()
    bridge.connect()

    // First disconnect → reconnect after 1s, which opens successfully (a live
    // port resets the attempt counter).
    MockPort.last().fireDisconnect()
    vi.advanceTimersByTime(1_000)
    expect(MockPort.instances.length).toBe(2)
    expect(bridge.connected).toBe(true)

    // Next disconnect should again wait only 1s (counter reset on open).
    MockPort.last().fireDisconnect()
    vi.advanceTimersByTime(1_000)
    expect(MockPort.instances.length).toBe(3)
  })

  it('schedules a reconnect when connectNative fails synchronously (lastError)', () => {
    vi.useFakeTimers()
    const { bridge } = newBridge()
    nextLastError = { message: 'host not found' }
    bridge.connect()
    // No usable port → not connected, and a reconnect is queued.
    expect(bridge.connected).toBe(false)

    // The next attempt succeeds once lastError clears.
    nextLastError = undefined
    vi.advanceTimersByTime(1_000)
    expect(bridge.connected).toBe(true)
  })

  it('ticks resetIdleTimer (MV3 keep-alive) every 20s while connected, stops on disconnect', () => {
    vi.useFakeTimers()
    const { bridge, resetIdleTimer } = newBridge()
    bridge.connect()

    expect(resetIdleTimer).not.toHaveBeenCalled()
    vi.advanceTimersByTime(20_000)
    expect(resetIdleTimer).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(20_000)
    expect(resetIdleTimer).toHaveBeenCalledTimes(2)

    bridge.disconnect()
    vi.advanceTimersByTime(60_000)
    expect(resetIdleTimer).toHaveBeenCalledTimes(2)
  })

  it('keep-alive stops after an unexpected disconnect (no SW to keep before reconnect)', () => {
    vi.useFakeTimers()
    const { bridge, resetIdleTimer } = newBridge()
    bridge.connect()
    vi.advanceTimersByTime(20_000)
    expect(resetIdleTimer).toHaveBeenCalledTimes(1)

    // Drop the port but DON'T advance into the reconnect — the keep-alive for
    // the dead port must not keep ticking.
    MockPort.last().fireDisconnect()
    const callsAtDrop = resetIdleTimer.mock.calls.length
    // Advance less than the 1s backoff so no new port (and its keep-alive) exists.
    vi.advanceTimersByTime(500)
    expect(resetIdleTimer.mock.calls.length).toBe(callsAtDrop)
  })

  it('emits connecting → open on connect, then closed on disconnect', () => {
    const { bridge } = newBridge()
    const states: string[] = []
    bridge.onStateChange(s => states.push(s))
    bridge.connect()
    expect(states).toEqual(['connecting', 'open'])
    MockPort.last().fireDisconnect()
    expect(states).toEqual(['connecting', 'open', 'closed'])
  })

  it('onStateChange reports connected:true exactly when open is emitted', () => {
    const { bridge } = newBridge()
    const seen: Array<{ state: string; connected: boolean }> = []
    bridge.onStateChange(s => seen.push({ state: s, connected: bridge.connected }))
    bridge.connect()
    expect(seen).toEqual([
      { state: 'connecting', connected: false },
      { state: 'open', connected: true },
    ])
  })

  it('a disconnect from a superseded port does not trigger reconnect', () => {
    vi.useFakeTimers()
    const { bridge } = newBridge()
    bridge.connect()
    const first = MockPort.last()

    // Reconnect explicitly — supersedes `first` with a new port.
    bridge.connect()
    expect(MockPort.instances.length).toBe(2)

    // A late onDisconnect from the OLD port must be ignored (no 3rd port).
    first.fireDisconnect()
    vi.advanceTimersByTime(60_000)
    expect(MockPort.instances.length).toBe(2)
    expect(bridge.connected).toBe(true)
  })
})
