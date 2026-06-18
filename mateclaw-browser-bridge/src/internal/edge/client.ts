/**
 * Edge WebSocket client for the MateClaw Browser Bridge (Native Host).
 *
 * Responsibilities:
 *   - Connect to the Control Plane with Bearer auth header
 *   - Send 'hello', await 'hello.ack', capture server-issued sessionId
 *   - Run heartbeat loop at server-specified interval
 *   - Expose a single AsyncIterableIterator<Message> (inbound()) for consumers
 *   - Track lastAckAt; throw HeartbeatTimeoutError only after a *generous*
 *     silence window AND a run of consecutive missed ticks (slow-network /
 *     main-thread-stall tolerance, P0#5) — so elevated RTT is not mistaken for
 *     a dead connection
 *
 * Single-reader contract: there is exactly ONE ws.on('message', ...) handler
 * in this file (#handleFrame). All consumers of inbound messages go through
 * the AsyncQueue exposed by inbound().
 */
import WebSocket from 'ws'
import { Kind, parse, make, type Message } from '../edgeproto/edgeproto.js'

// ── Typed errors ────────────────────────────────────────────────────────────

export class AuthError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AuthError'
  }
}

export class HeartbeatTimeoutError extends Error {
  constructor() {
    super('edge: heartbeat ack timeout — server silent past the watchdog window')
    this.name = 'HeartbeatTimeoutError'
  }
}

// ── AsyncQueue ───────────────────────────────────────────────────────────────
// ~30-line buffered queue. push() is synchronous. Consumers await via
// async iterator. Drop-oldest when at capacity.

class AsyncQueue<T> implements AsyncIterable<T> {
  readonly #buf: T[] = []
  readonly #waiters: Array<(v: IteratorResult<T>) => void> = []
  #done = false
  readonly #capacity: number

  constructor(capacity = 64) {
    this.#capacity = capacity
  }

  push(value: T): void {
    if (this.#done) return
    if (this.#waiters.length > 0) {
      this.#waiters.shift()!({ value, done: false })
      return
    }
    if (this.#buf.length >= this.#capacity) {
      // Drop oldest to preserve liveness
      this.#buf.shift()
    }
    this.#buf.push(value)
  }

  close(): void {
    this.#done = true
    for (const waiter of this.#waiters.splice(0)) {
      waiter({ value: undefined as unknown as T, done: true })
    }
  }

  [Symbol.asyncIterator](): AsyncIterator<T> {
    return {
      next: (): Promise<IteratorResult<T>> => {
        if (this.#buf.length > 0) {
          return Promise.resolve({ value: this.#buf.shift()!, done: false })
        }
        if (this.#done) {
          return Promise.resolve({ value: undefined as unknown as T, done: true })
        }
        return new Promise((resolve) => {
          this.#waiters.push(resolve)
        })
      },
    }
  }
}

// ── Client options ───────────────────────────────────────────────────────────

export interface ClientOptions {
  url: string
  authToken: string
  agentVersion?: string
  /** Dial timeout in ms (default 10 000). */
  dialTimeoutMs?: number
  /** Default heartbeat interval in ms; overridden by hello.ack. */
  heartbeatIntervalMs?: number
  /**
   * Watchdog death window as a multiple of the heartbeat interval (default 6).
   * The connection is only declared dead after the server has been silent for
   * more than `watchdogMultiplier × interval` AND that silence has spanned a run
   * of consecutive ticks. Widened from the original 3× so transient RTT spikes
   * or a stalled main thread on a slow machine don't cause a false disconnect.
   */
  watchdogMultiplier?: number
}

// ── Client ───────────────────────────────────────────────────────────────────

export class Client {
  readonly #opt: Required<ClientOptions>
  #ws: WebSocket | null = null
  #sessionId = ''
  #intervalMs: number
  /** epoch ms of most recent heartbeat.ack — Node is single-threaded, plain number is fine */
  #lastAckAt = 0
  /** Consecutive watchdog ticks that saw the server past the death window. */
  #missedAcks = 0
  #inbound = new AsyncQueue<Message>(64)

  constructor(opt: ClientOptions) {
    this.#opt = {
      url: opt.url,
      authToken: opt.authToken,
      agentVersion: opt.agentVersion ?? 'dev',
      dialTimeoutMs: opt.dialTimeoutMs ?? 10_000,
      heartbeatIntervalMs: opt.heartbeatIntervalMs ?? 10_000,
      watchdogMultiplier: opt.watchdogMultiplier ?? 6,
    }
    this.#intervalMs = this.#opt.heartbeatIntervalMs
  }

  /** The server-issued session id (empty until after connect()). */
  sessionId(): string { return this.#sessionId }

  /** The underlying WebSocket connection (used by Runner to send frames). */
  ws(): WebSocket {
    if (!this.#ws) throw new Error('edge: not connected')
    return this.#ws
  }

  /**
   * Send a Message to the Control Plane as a JSON-encoded WebSocket frame.
   * Throws if not connected.
   */
  send(msg: import('../edgeproto/edgeproto.js').Message): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      if (!this.#ws) { reject(new Error('edge: not connected')); return }
      this.#ws.send(JSON.stringify(msg), (err) => {
        if (err) reject(err)
        else resolve()
      })
    })
  }

  /**
   * Connect to the Control Plane. Sends hello, awaits hello.ack.
   * Returns the server-issued session id.
   * Throws AuthError on HTTP 401.
   */
  connect(): Promise<string> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.#opt.url, {
        headers: { Authorization: `Bearer ${this.#opt.authToken}` },
        handshakeTimeout: this.#opt.dialTimeoutMs,
      })

      const timeoutHandle = setTimeout(() => {
        ws.terminate()
        reject(new Error(`edge: connect timed out after ${this.#opt.dialTimeoutMs}ms`))
      }, this.#opt.dialTimeoutMs)

      ws.once('unexpected-response', (_req, res) => {
        clearTimeout(timeoutHandle)
        ws.terminate()
        if (res.statusCode === 401) {
          reject(new AuthError(`edge: server returned HTTP 401 — check auth token`))
        } else {
          reject(new Error(`edge: unexpected HTTP ${res.statusCode}`))
        }
      })

      ws.once('error', (err) => {
        clearTimeout(timeoutHandle)
        reject(new Error(`edge: WebSocket error: ${err.message}`))
      })

      ws.once('open', () => {
        clearTimeout(timeoutHandle)
        this.#ws = ws

        // Send hello
        const helloMsg = make({
          kind: Kind.Hello,
          payload: {
            agent_version: this.#opt.agentVersion,
            os: process.platform,
            arch: process.arch,
            auth: { scheme: 'pat', token: this.#opt.authToken },
          },
        })
        ws.send(JSON.stringify(helloMsg))

        // Await hello.ack — first message from server must be hello.ack
        ws.once('message', (data: Buffer) => {
          const msg = parse(data.toString())
          if (!msg || msg.kind !== Kind.HelloAck) {
            ws.close()
            reject(new Error(`edge: expected hello.ack, got ${msg?.kind ?? 'null'}`))
            return
          }
          const sid = String(msg.payload?.['session_id'] ?? '')
          if (!sid) {
            ws.close()
            reject(new Error('edge: hello.ack missing session_id'))
            return
          }
          this.#sessionId = sid

          // Capture server-issued heartbeat interval
          const hbMs = msg.payload?.['heartbeat_interval_ms']
          if (typeof hbMs === 'number' && hbMs > 0) {
            this.#intervalMs = hbMs
          }

          // Initialise lastAckAt so watchdog doesn't immediately fire
          this.#lastAckAt = Date.now()

          // Register the single message handler (the ONE ws.on('message') registration)
          ws.on('message', this.#handleFrame)

          // When the WS closes (cleanly or unexpectedly), drain the inbound queue
          // so that any consumer blocked on inbound() can observe the EOF.
          ws.once('close', () => {
            this.#inbound.close()
          })

          resolve(sid)
        })
      })
    })
  }

  /**
   * The SINGLE message handler for the WebSocket.
   * Dispatches:
   *   - heartbeat.ack → updates lastAckAt
   *   - everything else → pushes to #inbound queue
   */
  readonly #handleFrame = (data: Buffer): void => {
    const msg = parse(data.toString())
    if (!msg) return
    if (msg.kind === Kind.HeartbeatAck) {
      this.#lastAckAt = Date.now()
      return
    }
    this.#inbound.push(msg)
  }

  /**
   * Async iterable of inbound messages (excluding heartbeat.ack).
   * Exactly one ws.on('message') handler feeds this queue.
   */
  inbound(): AsyncIterable<Message> {
    return this.#inbound
  }

  /**
   * Run the heartbeat loop. Ticks every intervalMs.
   * - Sends heartbeat
   * - Declares the connection dead only after the server has been silent past
   *   the watchdog window (watchdogMultiplier × interval) for a run of
   *   consecutive ticks → HeartbeatTimeoutError. A single late ack (RTT spike /
   *   main-thread stall) is tolerated: any inbound ack resets the miss counter.
   * Resolves when signal is aborted (clean stop).
   */
  run(signal: AbortSignal): Promise<void> {
    if (!this.#ws) return Promise.reject(new Error('edge: run() before connect()'))

    return new Promise<void>((resolve, reject) => {
      if (signal.aborted) {
        resolve()
        return
      }

      let tickHandle: ReturnType<typeof setInterval> | null = null

      const stop = (err?: Error): void => {
        if (tickHandle !== null) {
          clearInterval(tickHandle)
          tickHandle = null
        }
        if (err) reject(err)
        else resolve()
      }

      signal.addEventListener('abort', () => stop(), { once: true })

      tickHandle = setInterval(() => {
        if (signal.aborted) {
          stop()
          return
        }

        // Send heartbeat
        const hb = make({ kind: Kind.Heartbeat, sessionId: this.#sessionId })
        try {
          this.#ws!.send(JSON.stringify(hb))
        } catch {
          stop(new Error('edge: failed to send heartbeat'))
          return
        }

        // Watchdog: only declare death after the server has stayed silent past
        // the (generous) window AND across a run of consecutive ticks. This
        // distinguishes a transient RTT spike / stalled main thread from a real
        // disconnect — a single late ack is tolerated and resets the counter.
        const silenceMs = Date.now() - this.#lastAckAt
        if (silenceMs > this.#opt.watchdogMultiplier * this.#intervalMs) {
          this.#missedAcks++
          // Require two consecutive over-window ticks so a one-off stall (where
          // the timer itself fired late, inflating silenceMs) cannot trip it.
          if (this.#missedAcks >= 2) {
            try { this.#ws!.close(1001, 'heartbeat-timeout') } catch {}
            stop(new HeartbeatTimeoutError())
          }
        } else {
          this.#missedAcks = 0
        }
      }, this.#intervalMs)
    })
  }

  /** Close the WebSocket connection gracefully. */
  async close(): Promise<void> {
    this.#inbound.close()
    if (this.#ws && this.#ws.readyState === WebSocket.OPEN) {
      await new Promise<void>((resolve) => {
        this.#ws!.once('close', () => resolve())
        this.#ws!.close(1000, 'client-close')
      })
    }
    this.#ws = null
  }

  /**
   * Reset the client state so it can be reconnected via connect().
   * Call this after close() before attempting a new connect() on the same instance.
   * Creates a fresh inbound queue so consumers can iterate again.
   */
  reset(): void {
    this.#ws = null
    this.#sessionId = ''
    this.#lastAckAt = 0
    this.#missedAcks = 0
    this.#intervalMs = this.#opt.heartbeatIntervalMs
    this.#inbound = new AsyncQueue<Message>(64)
  }
}
