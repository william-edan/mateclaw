/**
 * loopback-server 测试(连接根治:1 bridge 常驻 + loopback WS server)。
 *
 * 覆盖契约:
 *   - 契约2:Origin 鉴权 —— 正确 origin 放行,错误 origin 被 403 拒绝。
 *   - 契约1:帧格式复用 EdgeMessage JSON —— 扩展帧入队 / 后端下行回发扩展。
 *   - 契约3:upstream up/down 持续上报;新连接接入立即补发最近状态。
 *   - 契约5:单个扩展连接断开只 detach,绝不 close 帧队列(后端段毫发无伤)。
 *   - 单实例锁:同端口第二个实例 start() 命中 EADDRINUSE。
 */
import { describe, it, expect, afterEach } from 'vitest'
import { WebSocket as WsClient } from 'ws'
import { LoopbackServer, UPSTREAM_KIND } from './loopback-server.js'
import { MsgQueue } from '../runner/runner.js'
import { make, Kind, type Message } from '../edgeproto/edgeproto.js'

const ALLOWED_ORIGIN = 'chrome-extension://bjdhmojdiahokgfcaahphcjgcnffbonf'

// 测试用随机高端口,避开固定 18077 与并行测试冲突。
function randPort(): number {
  return 30000 + Math.floor(Math.random() * 20000)
}

let servers: LoopbackServer[] = []
let clients: WsClient[] = []

afterEach(async () => {
  for (const c of clients) { try { c.close() } catch { /* ignore */ } }
  clients = []
  for (const s of servers) { await s.close().catch(() => {}) }
  servers = []
})

function startServer(opts: { inboundQueue: MsgQueue; port: number }): Promise<LoopbackServer> {
  const s = new LoopbackServer({
    inboundQueue: opts.inboundQueue,
    port: opts.port,
    allowedOrigin: ALLOWED_ORIGIN,
    log: () => {}, // 静音测试日志
  })
  servers.push(s)
  return s.start().then((res) => {
    if (!res.ok) throw new Error(`server start failed: ${JSON.stringify(res)}`)
    return s
  })
}

function connect(port: number, origin: string): WsClient {
  const c = new WsClient(`ws://127.0.0.1:${port}/bridge`, { origin })
  clients.push(c)
  return c
}

function waitOpen(ws: WsClient): Promise<void> {
  return new Promise((resolve, reject) => {
    ws.once('open', () => resolve())
    ws.once('error', (e) => reject(e))
  })
}

/**
 * 持久收集某连接收到的所有 JSON 消息(避免 once('message') 在两次读之间漏帧)。
 * 返回 { all, waitFor } —— all 是已收消息数组,waitFor(pred) 轮询等待满足条件的那一条。
 */
function collect(ws: WsClient): {
  all: Array<Record<string, unknown>>
  waitFor: (pred: (m: Record<string, unknown>) => boolean, timeoutMs?: number) => Promise<Record<string, unknown>>
} {
  const all: Array<Record<string, unknown>> = []
  ws.on('message', (d: WsClient.RawData) => {
    try {
      all.push(JSON.parse(d.toString()) as Record<string, unknown>)
    } catch {
      /* ignore non-JSON */
    }
  })
  const waitFor = (
    pred: (m: Record<string, unknown>) => boolean,
    timeoutMs = 2000,
  ): Promise<Record<string, unknown>> =>
    new Promise((resolve, reject) => {
      const deadline = Date.now() + timeoutMs
      const tick = (): void => {
        const found = all.find(pred)
        if (found) { resolve(found); return }
        if (Date.now() >= deadline) { reject(new Error('collect.waitFor timeout')); return }
        setTimeout(tick, 10)
      }
      tick()
    })
  return { all, waitFor }
}

/** payload.kind === 'upstream' 的谓词。 */
function isUpstream(state: 'up' | 'down') {
  return (m: Record<string, unknown>): boolean => {
    const p = m['payload'] as Record<string, unknown> | undefined
    return p?.['kind'] === UPSTREAM_KIND && p?.['state'] === state
  }
}

describe('LoopbackServer — Origin 鉴权(契约2)', () => {
  it('正确 origin → 握手放行', async () => {
    const q = new MsgQueue()
    const port = randPort()
    await startServer({ inboundQueue: q, port })
    const ws = connect(port, ALLOWED_ORIGIN)
    await expect(waitOpen(ws)).resolves.toBeUndefined()
  })

  it('错误 origin → 握手被拒(不会 open)', async () => {
    const q = new MsgQueue()
    const port = randPort()
    await startServer({ inboundQueue: q, port })
    const ws = connect(port, 'chrome-extension://attacker')
    await expect(waitOpen(ws)).rejects.toBeDefined()
  })
})

describe('LoopbackServer — 帧路由(契约1)', () => {
  it('扩展帧被 enqueue 进 inboundQueue(EdgeMessage JSON 原样)', async () => {
    const q = new MsgQueue()
    const port = randPort()
    await startServer({ inboundQueue: q, port })
    const ws = connect(port, ALLOWED_ORIGIN)
    await waitOpen(ws)

    const action = make({ kind: Kind.ActionExecute, payload: { foo: 'bar' } })
    ws.send(JSON.stringify(action))

    const iter = q[Symbol.asyncIterator]()
    const got = await iter.next()
    expect(got.done).toBe(false)
    expect((got.value as Message).kind).toBe(Kind.ActionExecute)
    expect((got.value as Message).payload).toEqual({ foo: 'bar' })
  })

  it('ping/pong 应用层心跳被过滤,不入队(契约1)', async () => {
    const q = new MsgQueue()
    const port = randPort()
    await startServer({ inboundQueue: q, port })
    const ws = connect(port, ALLOWED_ORIGIN)
    await waitOpen(ws)

    ws.send(JSON.stringify({ kind: 'ping', ts: 1 }))
    ws.send(JSON.stringify(make({ kind: Kind.ActionExecute, payload: { n: 2 } })))

    const iter = q[Symbol.asyncIterator]()
    const got = await iter.next()
    // 第一条应是 action(ping 被吞),证明 ping 没占据队首。
    expect((got.value as Message).kind).toBe(Kind.ActionExecute)
    expect((got.value as Message).payload).toEqual({ n: 2 })
  })

  it('onInbound 把后端下行 Message 转发回当前扩展', async () => {
    const q = new MsgQueue()
    const port = randPort()
    const server = await startServer({ inboundQueue: q, port })
    const ws = connect(port, ALLOWED_ORIGIN)
    const sink = collect(ws)
    await waitOpen(ws)
    // open 后服务端会先补发一帧 upstream(初始 down)。
    await sink.waitFor(isUpstream('down'))

    const downlink = make({ kind: Kind.ActionResult, payload: { ok: true } })
    server.onInbound(downlink)

    const got = await sink.waitFor((m) => m['kind'] === Kind.ActionResult)
    expect(got['payload']).toEqual({ ok: true })
  })
})

describe('LoopbackServer — upstream 健康上报(契约3)', () => {
  it('新连接接入立即补发最近 upstream 状态', async () => {
    const q = new MsgQueue()
    const port = randPort()
    const server = await startServer({ inboundQueue: q, port })

    // 后端先变 up(此刻还没有扩展连接 → 仅记录 lastUpstream)。
    server.onUpstreamState('up')

    const ws = connect(port, ALLOWED_ORIGIN)
    const sink = collect(ws)
    await waitOpen(ws)
    const got = await sink.waitFor(isUpstream('up'))
    expect(got['kind']).toBe(Kind.Unknown)
  })

  it('已连扩展能持续收到 up→down 翻转', async () => {
    const q = new MsgQueue()
    const port = randPort()
    const server = await startServer({ inboundQueue: q, port })
    const ws = connect(port, ALLOWED_ORIGIN)
    const sink = collect(ws)
    await waitOpen(ws)
    await sink.waitFor(isUpstream('down')) // 初始 down 补发

    server.onUpstreamState('up')
    await sink.waitFor(isUpstream('up'))

    server.onUpstreamState('down')
    // 等到"初始 down 之后"再出现的 down(即第 2 条 down)。
    await sink.waitFor((m) => isUpstream('down')(m) && sink.all.filter(isUpstream('down')).length >= 2)
  })
})

describe('LoopbackServer — 生命周期硬隔离(契约5)', () => {
  it('扩展连接断开后,inboundQueue 仍开放(后端段不受影响)', async () => {
    const q = new MsgQueue()
    const port = randPort()
    await startServer({ inboundQueue: q, port })

    const ws = connect(port, ALLOWED_ORIGIN)
    await waitOpen(ws)
    ws.close()
    // 等连接 close 在服务端落地。
    await new Promise((r) => setTimeout(r, 100))

    // 队列仍开放:enqueue 一条(模拟另一个扩展连上来发帧)能被消费,证明没被 close。
    const probe = make({ kind: Kind.ActionExecute, payload: { alive: true } })
    q.enqueue(probe)
    const iter = q[Symbol.asyncIterator]()
    const got = await iter.next()
    expect(got.done).toBe(false)
    expect((got.value as Message).payload).toEqual({ alive: true })
  })

  it('新扩展连接取代旧连接,后端下行只发给新连接', async () => {
    const q = new MsgQueue()
    const port = randPort()
    const server = await startServer({ inboundQueue: q, port })

    const ws1 = connect(port, ALLOWED_ORIGIN)
    const sink1 = collect(ws1)
    await waitOpen(ws1)
    await sink1.waitFor(isUpstream('down')) // 初始 down

    const ws2 = connect(port, ALLOWED_ORIGIN)
    const sink2 = collect(ws2)
    await waitOpen(ws2)
    await sink2.waitFor(isUpstream('down')) // 初始 down(给新连接)
    // 等服务端把 current 切到 ws2、关掉 ws1。
    await new Promise((r) => setTimeout(r, 100))

    const downlink = make({ kind: Kind.ActionResult, payload: { which: 'new' } })
    server.onInbound(downlink)

    const got = await sink2.waitFor((m) => m['kind'] === Kind.ActionResult)
    expect(got['payload']).toEqual({ which: 'new' })
    // 旧连接绝不应收到这条后端下行(current 已切走)。
    expect(sink1.all.some((m) => m['kind'] === Kind.ActionResult)).toBe(false)
  })
})

describe('LoopbackServer — 单实例锁(EADDRINUSE)', () => {
  it('同端口第二个实例 start() 返回 EADDRINUSE', async () => {
    const q1 = new MsgQueue()
    const port = randPort()
    await startServer({ inboundQueue: q1, port })

    const q2 = new MsgQueue()
    const second = new LoopbackServer({
      inboundQueue: q2,
      port,
      allowedOrigin: ALLOWED_ORIGIN,
      log: () => {},
    })
    servers.push(second)
    const res = await second.start()
    expect(res.ok).toBe(false)
    if (!res.ok) expect(res.reason).toBe('EADDRINUSE')
  })
})
