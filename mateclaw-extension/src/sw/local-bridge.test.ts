import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { LocalBridgeClient, LOCAL_BRIDGE_URL } from './local-bridge'

// Controllable mock WebSocket — same idiom as direct-bridge.test.ts. Tests drive
// open/message/close manually; reconnect creates new tracked instances.
class MockWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  readonly CONNECTING = 0
  readonly OPEN = 1
  readonly CLOSING = 2
  readonly CLOSED = 3

  static instances: MockWebSocket[] = []

  readyState = MockWebSocket.CONNECTING
  sent: string[] = []
  closeCalled = false

  onopen: ((ev?: unknown) => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onclose: ((ev?: unknown) => void) | null = null
  onerror: ((ev?: unknown) => void) | null = null

  constructor(
    readonly url: string,
    readonly protocols?: string | string[],
  ) {
    MockWebSocket.instances.push(this)
  }

  send(data: string): void {
    this.sent.push(data)
  }

  close(): void {
    this.closeCalled = true
    this.readyState = MockWebSocket.CLOSED
  }

  fireOpen(): void {
    this.readyState = MockWebSocket.OPEN
    this.onopen?.()
  }

  fireMessage(obj: unknown): void {
    this.onmessage?.({ data: JSON.stringify(obj) })
  }

  fireClose(): void {
    this.readyState = MockWebSocket.CLOSED
    this.onclose?.()
  }

  static last(): MockWebSocket {
    const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1]
    if (!ws) throw new Error('no MockWebSocket instance')
    return ws
  }
}

function newClient(resetIdleTimer: () => void = () => {}) {
  return new LocalBridgeClient({
    WebSocketImpl: MockWebSocket as unknown as typeof WebSocket,
    resetIdleTimer,
  })
}

/** Bridge upstream-health control frame (跨组契约3): NO v/msg_id, has kind+state. */
function upstream(state: 'up' | 'down') {
  return { kind: 'upstream', state }
}

/** A genuine business EdgeMessage (v:1 + msg_id) — must reach onMessage. */
function edgeMsg(kind: string) {
  return { v: 1, msg_id: 'm1', kind, ts: 0, trace_id: 't', session_id: '', payload: {} }
}

describe('LocalBridgeClient', () => {
  beforeEach(() => {
    MockWebSocket.instances = []
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('connects to LOCAL_BRIDGE_URL (the /bridge path) by default, with no subprotocol/bearer', () => {
    const c = newClient()
    c.connect()
    const ws = MockWebSocket.last()
    expect(ws.url).toBe(LOCAL_BRIDGE_URL)
    // 必须带 /bridge —— loopback server 按 path 校验握手,裸根会被直接拒(连不上)。
    expect(ws.url).toContain('/bridge')
    // Loopback auth is by Origin (契约2): no subprotocol, no bearer token.
    expect(ws.protocols).toBeUndefined()
  })

  // ── 本次修复核心:连接中(尚未 OPEN)就维持 SW 存活 ───────────────────────────────
  it('keeps the SW alive (resetIdleTimer) DURING the connecting phase, before the socket opens', () => {
    vi.useFakeTimers()
    const resetIdleTimer = vi.fn()
    const c = newClient(resetIdleTimer)
    c.connect()
    // Socket is still CONNECTING (the bridge has not come up / not accepted yet).
    expect(MockWebSocket.last().readyState).toBe(MockWebSocket.CONNECTING)
    // Old behaviour started keepalive only in onopen → SW would be reclaimed in
    // ~30s before a late-starting bridge appeared. New behaviour ticks while
    // connecting so the fast reconnect survives the cold-start race.
    vi.advanceTimersByTime(20_000)
    expect(resetIdleTimer).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(20_000)
    expect(resetIdleTimer).toHaveBeenCalledTimes(2)
  })

  it('does NOT stop keepalive on an unexpected close — it ticks through the reconnect gap', () => {
    vi.useFakeTimers()
    const resetIdleTimer = vi.fn()
    const c = newClient(resetIdleTimer)
    c.connect()
    // Bridge drops/never opened → unexpected close. Auto-reconnect must keep the
    // SW alive across the gap so the loop runs to a bridge that appears later.
    MockWebSocket.last().fireClose()
    vi.advanceTimersByTime(60_000)
    // With the old code (stopKeepAlive in handleClose + no keepalive in openSocket)
    // this would be 0; now the reconnected socket keeps ticking.
    expect(resetIdleTimer.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  it('reconnects on unexpected close with the tight 100ms→2s backoff', () => {
    vi.useFakeTimers()
    const c = newClient()
    c.connect()
    expect(MockWebSocket.instances.length).toBe(1)

    // First close → reconnect after 100ms.
    MockWebSocket.last().fireClose()
    vi.advanceTimersByTime(99)
    expect(MockWebSocket.instances.length).toBe(1)
    vi.advanceTimersByTime(1)
    expect(MockWebSocket.instances.length).toBe(2)

    // Second close → backoff grows to 200ms.
    MockWebSocket.last().fireClose()
    vi.advanceTimersByTime(199)
    expect(MockWebSocket.instances.length).toBe(2)
    vi.advanceTimersByTime(1)
    expect(MockWebSocket.instances.length).toBe(3)
  })

  it('intentional disconnect() stops keepalive AND suppresses reconnect', () => {
    vi.useFakeTimers()
    const resetIdleTimer = vi.fn()
    const c = newClient(resetIdleTimer)
    c.connect()
    MockWebSocket.last().fireOpen()
    resetIdleTimer.mockClear()

    c.disconnect()
    expect(MockWebSocket.last().closeCalled).toBe(true)

    vi.advanceTimersByTime(60_000)
    expect(resetIdleTimer).not.toHaveBeenCalled() // keepalive stopped
    expect(MockWebSocket.instances.length).toBe(1) // no reconnect
    expect(c.connected).toBe(false)
  })

  // ── 端到端 connected 门控(契约3):不能只看本地 socket OPEN ────────────────────────
  it('connected is true ONLY when the loopback is OPEN AND the bridge reports upstream up', () => {
    const c = newClient()
    c.connect()
    const ws = MockWebSocket.last()
    ws.fireOpen()
    expect(c.ipcOpen).toBe(true)
    expect(c.connected).toBe(false) // upstream unknown → no false positive

    ws.fireMessage(upstream('up'))
    expect(c.connected).toBe(true)

    ws.fireMessage(upstream('down'))
    expect(c.connected).toBe(false) // backend dropped, even though socket OPEN
  })

  it('upstream control frames never reach onMessage; business EdgeMessages do', () => {
    const c = newClient()
    const cb = vi.fn()
    c.onMessage(cb)
    c.connect()
    const ws = MockWebSocket.last()
    ws.fireOpen()

    ws.fireMessage(upstream('up')) // control-plane → consumed, NOT delivered
    expect(cb).not.toHaveBeenCalled()

    ws.fireMessage(edgeMsg('action.execute')) // business → delivered
    expect(cb).toHaveBeenCalledTimes(1)
    expect(cb.mock.calls[0]?.[0]?.kind).toBe('action.execute')
  })

  it('a fresh loopback reconnect resets upstream to false until the bridge re-announces it', () => {
    vi.useFakeTimers()
    const c = newClient()
    c.connect()
    let ws = MockWebSocket.last()
    ws.fireOpen()
    ws.fireMessage(upstream('up'))
    expect(c.connected).toBe(true)

    // Loopback drops; the SW must not keep reporting connected on a fresh socket
    // until the bridge re-confirms its backend link (契约3).
    ws.fireClose()
    vi.advanceTimersByTime(100)
    ws = MockWebSocket.last()
    ws.fireOpen()
    expect(c.ipcOpen).toBe(true)
    expect(c.connected).toBe(false) // upstream reset on reconnect — no stale true
  })
})
