import { describe, expect, it, vi } from 'vitest'
import { OffscreenBridgeProxy } from './offscreen-bridge-proxy'
import { EdgeMessageKind, makeEdgeMessage, type EdgeMessage } from '../shared/edge-protocol'
import {
  OFFSCREEN_CONNECT,
  OFFSCREEN_DISCONNECT,
  OFFSCREEN_DISCONNECTED,
  OFFSCREEN_SEND,
  OFFSCREEN_STATE,
} from '../shared/offscreen-protocol'

// Drain the ensureDocument (hasDocument→createDocument→post) microtask chain and
// then a macrotask, deterministically — robust under parallel load.
const flush = async (): Promise<void> => {
  for (let i = 0; i < 8; i += 1) await Promise.resolve()
  await new Promise<void>(r => setTimeout(r, 0))
}

function fakeChrome(
  opts: { hasDoc?: boolean; createReject?: unknown; noOffscreen?: boolean } = {},
) {
  const sent: unknown[] = []
  const createDocument = vi.fn(async () => {
    if (opts.createReject) throw opts.createReject
  })
  const hasDocument = vi.fn(async () => opts.hasDoc ?? false)
  const runtime = {
    sendMessage: vi.fn(async (m: unknown) => {
      sent.push(m)
    }),
  }
  const offscreen = opts.noOffscreen
    ? undefined
    : { createDocument, hasDocument, Reason: { BLOBS: 'BLOBS' } }
  const chrome = { runtime, offscreen } as unknown as typeof globalThis.chrome
  return { chrome, sent, createDocument, hasDocument }
}

function makeProxy(chrome: typeof globalThis.chrome) {
  return new OffscreenBridgeProxy({
    deviceId: 'dev-1',
    deviceName: 'Test Box',
    agentVersion: '0.1.5',
    chrome,
  })
}

const frame = (): EdgeMessage => makeEdgeMessage({ kind: EdgeMessageKind.Heartbeat })

describe('OffscreenBridgeProxy', () => {
  it('connect: ensures the offscreen doc then posts CONNECT with creds', async () => {
    const env = fakeChrome()
    const proxy = makeProxy(env.chrome)

    proxy.connect('wss://host/edge', 'pat-1')
    await flush()

    expect(env.createDocument).toHaveBeenCalledWith({
      url: 'offscreen.html',
      reasons: ['BLOBS'],
      justification: expect.stringContaining('WebSocket'),
    })
    expect(env.sent).toContainEqual({
      type: OFFSCREEN_CONNECT,
      serverUrl: 'wss://host/edge',
      pat: 'pat-1',
      deviceId: 'dev-1',
      deviceName: 'Test Box',
      agentVersion: '0.1.5',
      // Default transport is now stamped on every CONNECT (跨组契约). Absent ⇒
      // 'direct' on the wire for back-compat, but the proxy stamps it explicitly.
      transport: 'direct',
    })
  })

  it('connect: skips createDocument when one already exists', async () => {
    const env = fakeChrome({ hasDoc: true })
    const proxy = makeProxy(env.chrome)

    proxy.connect('wss://host/edge', 'pat-1')
    await flush()

    expect(env.createDocument).not.toHaveBeenCalled()
    expect(env.sent).toContainEqual(expect.objectContaining({ type: OFFSCREEN_CONNECT }))
  })

  it('ingestRelay(STATE) updates connected + fires onStateChange', () => {
    const env = fakeChrome()
    const proxy = makeProxy(env.chrome)
    const states: string[] = []
    proxy.onStateChange(s => states.push(s))

    expect(proxy.connected).toBe(false)
    proxy.ingestRelay({ type: OFFSCREEN_STATE, state: 'open', connected: true })
    expect(proxy.connected).toBe(true)
    expect(states).toContain('open')
  })

  it('ingestRelay(DISCONNECTED) clears connected + fires onDisconnect', () => {
    const env = fakeChrome()
    const proxy = makeProxy(env.chrome)
    let dc = 0
    proxy.onDisconnect(() => (dc += 1))
    proxy.ingestRelay({ type: OFFSCREEN_STATE, state: 'open', connected: true })

    proxy.ingestRelay({ type: OFFSCREEN_DISCONNECTED })
    expect(dc).toBe(1)
    expect(proxy.connected).toBe(false)
  })

  it('ingestRelay does NOT deliver inbound frames (those go top-level)', () => {
    const env = fakeChrome()
    const proxy = makeProxy(env.chrome)
    const got: EdgeMessage[] = []
    proxy.onMessage(m => got.push(m))
    // An inbound-shaped relay is not the proxy's job; ingestRelay only handles
    // state. Passing anything but STATE/DISCONNECTED is a no-op.
    proxy.ingestRelay({ type: 'mateclaw.offscreen.inbound', message: frame() } as never)
    expect(got).toEqual([])
  })

  it('send posts OFFSCREEN_SEND (surface parity; sendUp normally posts directly)', () => {
    const env = fakeChrome()
    const proxy = makeProxy(env.chrome)
    const f = frame()
    proxy.send(f)
    expect(env.sent).toContainEqual({ type: OFFSCREEN_SEND, message: f })
  })

  it('disconnect posts DISCONNECT and emits closed', () => {
    const env = fakeChrome()
    const proxy = makeProxy(env.chrome)
    proxy.ingestRelay({ type: OFFSCREEN_STATE, state: 'open', connected: true })
    const states: string[] = []
    proxy.onStateChange(s => states.push(s))

    proxy.disconnect()

    expect(env.sent).toContainEqual({ type: OFFSCREEN_DISCONNECT })
    expect(proxy.connected).toBe(false)
    expect(states).toContain('closed')
  })

  it('swallows the "single offscreen document" create race', async () => {
    const env = fakeChrome({
      createReject: new Error('Only a single offscreen document may be created.'),
    })
    const proxy = makeProxy(env.chrome)

    proxy.connect('wss://host/edge', 'pat-1')
    await flush()

    // ensure resolved despite the create rejection → CONNECT still posted.
    expect(env.sent).toContainEqual(expect.objectContaining({ type: OFFSCREEN_CONNECT }))
  })

  it('offscreen API unavailable -> emits closed, never posts CONNECT', async () => {
    const env = fakeChrome({ noOffscreen: true })
    const proxy = makeProxy(env.chrome)
    const states: string[] = []
    proxy.onStateChange(s => states.push(s))

    proxy.connect('wss://host/edge', 'pat-1')
    await flush()

    expect(env.sent).not.toContainEqual(expect.objectContaining({ type: OFFSCREEN_CONNECT }))
    expect(states).toContain('closed')
    expect(proxy.connected).toBe(false)
  })
})
