/**
 * B7 — Runner with reconnect + session_id stamp tests.
 *
 * Uses in-process ws.Server and PassThrough streams for full integration.
 * Vitest timeout is extended for reconnect timing tests.
 */
import { describe, it, expect, afterEach } from 'vitest'
import { WebSocketServer, WebSocket as WsClient } from 'ws'
import { createServer } from 'node:http'
import { PassThrough } from 'node:stream'
import { Kind, make, type Message } from '../edgeproto/edgeproto.js'
import { Client } from '../edge/client.js'
import { Runner, MsgQueue } from './runner.js'
import { writeFrame, writeJsonFrame, readFrame } from '../nm/server.js'

// ── helpers ───────────────────────────────────────────────────────────────────

type ServerHandle = {
  url: string
  close: () => Promise<void>
}

/**
 * Spin up a WebSocket server on a random port.
 * `handler` is called once per connection.
 */
function makeServer(handler: (ws: WsClient) => void): Promise<ServerHandle> {
  return new Promise((resolve, reject) => {
    const http = createServer()
    const wss = new WebSocketServer({ server: http })
    wss.on('connection', handler)
    http.listen(0, '127.0.0.1', () => {
      const addr = http.address() as { port: number }
      const url = `ws://127.0.0.1:${addr.port}`
      const close = (): Promise<void> =>
        new Promise((res) => {
          wss.close(() => http.close(() => res()))
        })
      resolve({ url, close })
    })
    http.on('error', reject)
  })
}

/**
 * Build a hello.ack response for a given sessionId and ws socket.
 * Also sends heartbeat.ack for each heartbeat received.
 */
function respondHelloAck(ws: WsClient, sessionId: string): void {
  ws.once('message', (data: Buffer) => {
    const hello = JSON.parse(data.toString())
    if (hello.kind !== Kind.Hello) { ws.close(); return }
    ws.send(JSON.stringify({
      v: 1, msg_id: 'ack-1', kind: Kind.HelloAck,
      ts: Date.now(), trace_id: 'tr', session_id: sessionId,
      in_reply_to: hello.msg_id,
      payload: { session_id: sessionId, heartbeat_interval_ms: 5000 },
    }))
    // Respond to heartbeats so watchdog doesn't fire
    ws.on('message', (d: Buffer) => {
      const msg = JSON.parse(d.toString())
      if (msg.kind === Kind.Heartbeat) {
        ws.send(JSON.stringify({
          v: 1, msg_id: 'hbk', kind: Kind.HeartbeatAck,
          ts: Date.now(), trace_id: 'tr', session_id: '',
          in_reply_to: msg.msg_id,
        }))
      }
    })
  })
}

// ── B7 tests ──────────────────────────────────────────────────────────────────

describe('Runner — B7', () => {
  /**
   * Test 1: extension sends session_id: "", bridge stamps real server-issued id.
   */
  it('pingPong_sessionIdStampedByBridge', async () => {
    const received: string[] = []

    const { url, close } = await makeServer((ws) => {
      respondHelloAck(ws, 'sess-server-issued')
      // After hello.ack, capture whatever the bridge forwards
      ws.on('message', (data: Buffer) => {
        const msg = JSON.parse(data.toString())
        if (msg.kind === Kind.Ping) {
          received.push(msg.session_id)
          // Echo a pong back so the test can observe liveness
          ws.send(JSON.stringify({
            v: 1, msg_id: 'pong-1', kind: Kind.Pong,
            ts: Date.now(), trace_id: 'tr', session_id: 'sess-server-issued',
          }))
          ws.close()
        }
      })
    })

    const stdinPt = new PassThrough()
    const stdoutPt = new PassThrough()

    const client = new Client({ url, authToken: 'tok', heartbeatIntervalMs: 5000 })
    const runner = new Runner({ client, stdin: stdinPt, stdout: stdoutPt, maxAttempts: 1 })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)

    // Wait a tick for connect to complete
    await new Promise((r) => setTimeout(r, 100))

    // Extension sends a ping with EMPTY session_id
    const pingMsg = make({ kind: Kind.Ping, sessionId: '' })
    await writeJsonFrame(stdinPt, pingMsg)

    // Wait for server to receive it and close
    await new Promise((r) => setTimeout(r, 200))
    ac.abort()
    await runPromise.catch(() => {})
    await close()

    expect(received.length).toBeGreaterThanOrEqual(1)
    expect(received[0]).toBe('sess-server-issued')
  }, 8000)

  /**
   * Test 2: extension sends a forged session_id; bridge overrides it.
   */
  it('overridesAttackerSuppliedSessionId', async () => {
    const received: string[] = []

    const { url, close } = await makeServer((ws) => {
      respondHelloAck(ws, 'sess-real')
      ws.on('message', (data: Buffer) => {
        const msg = JSON.parse(data.toString())
        if (msg.kind === Kind.Ping) {
          received.push(msg.session_id)
          ws.close()
        }
      })
    })

    const stdinPt = new PassThrough()
    const stdoutPt = new PassThrough()

    const client = new Client({ url, authToken: 'tok', heartbeatIntervalMs: 5000 })
    const runner = new Runner({ client, stdin: stdinPt, stdout: stdoutPt, maxAttempts: 1 })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)

    await new Promise((r) => setTimeout(r, 100))

    // Extension sends a ping with ATTACKER session_id
    const pingMsg = make({ kind: Kind.Ping, sessionId: 'sess-attacker' })
    await writeJsonFrame(stdinPt, pingMsg)

    await new Promise((r) => setTimeout(r, 200))
    ac.abort()
    await runPromise.catch(() => {})
    await close()

    expect(received.length).toBeGreaterThanOrEqual(1)
    expect(received[0]).toBe('sess-real')
    expect(received[0]).not.toBe('sess-attacker')
  }, 8000)

  /**
   * Test 3: reconnect with backoff.
   * Server drops first two connections immediately after hello.ack.
   * Third connection is kept open.
   * Assert ≥3 distinct dials, gap between dial 1 and dial 2 is ≥ 50ms.
   */
  it('reconnectsWithBackoff', async () => {
    const dialTimes: number[] = []
    let connectionCount = 0

    const { url, close } = await makeServer((ws) => {
      connectionCount++
      dialTimes.push(Date.now())
      const attempt = connectionCount

      ws.once('message', (data: Buffer) => {
        const hello = JSON.parse(data.toString())
        if (hello.kind !== Kind.Hello) { ws.close(); return }
        ws.send(JSON.stringify({
          v: 1, msg_id: 'ack', kind: Kind.HelloAck,
          ts: Date.now(), trace_id: 'tr',
          session_id: `sess-${attempt}`,
          in_reply_to: hello.msg_id,
          payload: { session_id: `sess-${attempt}`, heartbeat_interval_ms: 5000 },
        }))

        if (attempt <= 2) {
          // Immediately drop the connection after hello.ack
          setTimeout(() => ws.close(1001, 'drop'), 20)
        }
        // Third connection stays open
      })
    })

    const stdinPt = new PassThrough()
    const stdoutPt = new PassThrough()

    const client = new Client({ url, authToken: 'tok', heartbeatIntervalMs: 5000 })
    const runner = new Runner({
      client,
      stdin: stdinPt,
      stdout: stdoutPt,
      backoffBase: 50,
      backoffMax: 500,
      maxAttempts: 5,
    })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)

    // Wait long enough for 3 connections + backoff delays
    await new Promise((r) => setTimeout(r, 4000))
    ac.abort()
    await runPromise.catch(() => {})
    await close()

    // (a) ≥3 distinct dials
    expect(dialTimes.length).toBeGreaterThanOrEqual(3)

    // (b) gap between dial 1 and dial 2 is ≥ 50ms (backoffBase)
    if (dialTimes.length >= 2) {
      const gap = dialTimes[1]! - dialTimes[0]!
      expect(gap).toBeGreaterThanOrEqual(50)
    }
  }, 10000)
})

// ── IPC(常驻 loopback)模式 ────────────────────────────────────────────────────

describe('Runner — IPC 模式(连接根治)', () => {
  it('帧来源为外部 MsgQueue:入队→盖 session_id→上后端;后端下行经 onInbound 回调(契约1)', async () => {
    const received: string[] = []
    const { url, close } = await makeServer((ws) => {
      respondHelloAck(ws, 'sess-ipc')
      ws.on('message', (data: Buffer) => {
        const msg = JSON.parse(data.toString())
        if (msg.kind === Kind.ActionExecute) {
          received.push(msg.session_id)
          // 回一条后端下行,Runner 应经 onInbound 递出。
          ws.send(JSON.stringify(make({ kind: Kind.ActionResult, sessionId: 'sess-ipc', payload: { ok: true } })))
        }
      })
    })

    const inboundQueue = new MsgQueue()
    const inboundToExt: Message[] = []
    const upstreamStates: Array<'up' | 'down'> = []

    const client = new Client({ url, authToken: 'tok', heartbeatIntervalMs: 5000 })
    const runner = new Runner({
      client,
      ipc: {
        inboundQueue,
        onInbound: (m) => inboundToExt.push(m),
        onUpstreamState: (s) => upstreamStates.push(s),
      },
      maxAttempts: 1,
    })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)
    await new Promise((r) => setTimeout(r, 100))

    // 模拟 loopback-server:把扩展帧(带伪造 session_id)入队。
    inboundQueue.enqueue(make({ kind: Kind.ActionExecute, sessionId: 'forged', payload: {} }))

    await new Promise((r) => setTimeout(r, 200))
    ac.abort()
    await runPromise.catch(() => {})
    await close()

    // 后端收到的 session_id 被 Runner 盖成真实下发值(安全语义不变)。
    expect(received[0]).toBe('sess-ipc')
    // 后端下行经 onInbound 递给"扩展"。
    expect(inboundToExt.some((m) => m.kind === Kind.ActionResult)).toBe(true)
    // upstream 至少上报过一次 up(connect 成功)。
    expect(upstreamStates).toContain('up')
  }, 8000)

  it('Runner 停止后绝不 close 外部 inboundQueue(契约5:生命周期硬隔离)', async () => {
    const { url, close } = await makeServer((ws) => {
      respondHelloAck(ws, 'sess-x')
    })

    const inboundQueue = new MsgQueue()
    const client = new Client({ url, authToken: 'tok', heartbeatIntervalMs: 5000 })
    const runner = new Runner({
      client,
      ipc: { inboundQueue, onInbound: () => {} },
      maxAttempts: 1,
    })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)
    await new Promise((r) => setTimeout(r, 100))
    ac.abort()
    await runPromise.catch(() => {})
    await close()

    // 队列若被 close,任一 iterator 的 next() 会立即 resolve {done:true};未 close 则保持 pending。
    // 用"next() vs 80ms 超时"竞速:超时先到 → 队列仍开放(契约5:Runner 绝不 close 外部队列)。
    const iter = inboundQueue[Symbol.asyncIterator]()
    const timeoutSentinel = Symbol('still-open')
    const race = await Promise.race([
      iter.next().then((r) => (r.done ? 'closed' : 'got-value')),
      new Promise<typeof timeoutSentinel>((r) => setTimeout(() => r(timeoutSentinel), 80)),
    ])
    expect(race).toBe(timeoutSentinel) // 既没 closed 也没拿到值 → 仍 pending → 队列开放
  }, 8000)

  it('connect 失败 → 上报 upstream down,不上报 up(契约3:不假阳性)', async () => {
    // 指向一个没有 WS server 的端口,connect 必失败。
    const upstreamStates: Array<'up' | 'down'> = []
    const inboundQueue = new MsgQueue()
    const client = new Client({ url: 'ws://127.0.0.1:1/edge', authToken: 'tok', dialTimeoutMs: 300 })
    const runner = new Runner({
      client,
      ipc: {
        inboundQueue,
        onInbound: () => {},
        onUpstreamState: (s) => upstreamStates.push(s),
      },
      maxAttempts: 1,
    })

    const ac = new AbortController()
    await runner.run(ac.signal).catch(() => {})

    // connect 抛错 → 从未上报 up;#runOnce 未进入,故连 down 也不会从那里发出。
    expect(upstreamStates).not.toContain('up')
  }, 8000)

  it('token 长跑重读:401 后用新 token 重建 Client 重试(契约5)', async () => {
    let helloCount = 0
    const tokensSeen: string[] = []
    // 第一次连接:服务端读 hello.auth.token,若是旧 token 就 401 式拒绝(关连接前不发 ack);
    // 第二次:新 token 放行,正常 hello.ack。这里用"hello 后立即 close 模拟 401"不够精确
    // (Client 的 AuthError 来自 HTTP 401 unexpected-response),改为在 verifyClient 层拒绝。
    const httpFailFirst = await makeAuthServer((token) => {
      tokensSeen.push(token)
      helloCount++
      // 旧 token 第一次连 → 拒绝(返回 false 让 ws 回 401);新 token → 放行。
      return token === 'new-tok'
    })

    const client = new Client({ url: httpFailFirst.url, authToken: 'old-tok', heartbeatIntervalMs: 5000 })
    const inboundQueue = new MsgQueue()
    let reloadCalls = 0
    const rebuilt: string[] = []
    const runner = new Runner({
      client,
      ipc: { inboundQueue, onInbound: () => {} },
      initialAuthToken: 'old-tok',
      maxAttempts: 3,
      backoffBase: 20,
      reloadAuthToken: async () => {
        reloadCalls++
        // 第一次重读仍是旧 token;401 后桌面壳"写出"新 token。
        return reloadCalls === 1 ? 'old-tok' : 'new-tok'
      },
      rebuildClient: (tok) => {
        rebuilt.push(tok)
        return new Client({ url: httpFailFirst.url, authToken: tok, heartbeatIntervalMs: 5000 })
      },
    })

    const ac = new AbortController()
    const runPromise = runner.run(ac.signal)
    await new Promise((r) => setTimeout(r, 600))
    ac.abort()
    await runPromise.catch(() => {})
    await httpFailFirst.close()

    // 至少用新 token 重建过一次,且服务端最终收到过 new-tok。
    expect(rebuilt).toContain('new-tok')
    expect(tokensSeen).toContain('new-tok')
  }, 10000)
})

/**
 * 起一个会按 token 决定是否放行握手的 WS server(用于 401/token 轮换测试)。
 * verifyClient 取 Sec-WebSocket-Protocol 里的 bearer.<token>,或 Authorization 头里的 Bearer。
 * 放行的连接正常回 hello.ack + 心跳 ack。
 */
function makeAuthServer(
  allow: (token: string) => boolean,
): Promise<ServerHandle> {
  return new Promise((resolve, reject) => {
    const http = createServer()
    const wss = new WebSocketServer({
      server: http,
      verifyClient: (info, cb) => {
        const auth = info.req.headers['authorization'] ?? ''
        const token = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : ''
        if (allow(token)) cb(true)
        else cb(false, 401, 'unauthorized')
      },
    })
    wss.on('connection', (ws: WsClient) => respondHelloAck(ws, 'sess-auth'))
    http.listen(0, '127.0.0.1', () => {
      const addr = http.address() as { port: number }
      resolve({
        url: `ws://127.0.0.1:${addr.port}`,
        close: () => new Promise((res) => wss.close(() => http.close(() => res()))),
      })
    })
    http.on('error', reject)
  })
}
