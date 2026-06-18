/**
 * Runner — orchestrates the Native Host pipeline.
 *
 * Responsibilities:
 *   - Connect the Client to the Control Plane.
 *   - Pump stdin frames (Chrome NM) → parse JSON → stamp session_id → send via Client.
 *   - Pump inbound messages from Client → serialise JSON → write NM frames to stdout.
 *   - Run the Client heartbeat loop concurrently.
 *   - On any I/O or heartbeat error, sleep with exponential backoff + ±20% jitter,
 *     then reconnect — forever by default (slow-network resilience P0#5). A flaky
 *     or slow link must NOT make the whole Native Host exit; only an abort signal
 *     (intentional stop) ends the loop cleanly. An optional maxAttempts cap exists
 *     purely for tests; 0/undefined means "retry indefinitely".
 *
 * Security invariant (Codex P0-1):
 *   The Native Host is the SOLE owner of session_id. Whatever the Extension sends
 *   in session_id is unconditionally overwritten with the server-issued id.
 *
 * Single-reader invariant:
 *   Runner consumes client.inbound() (AsyncIterable). It does NOT attach any
 *   ws.on('message', ...) handler directly.
 *
 * Promise.race policy:
 *   Promise.race is used ONLY at the lifecycle level (runOnce) to detect the first
 *   failing pump. It is NOT used on the action message stream.
 *
 * Reconnect design:
 *   stdin is a persistent pipe (Chrome Native Messaging) — it stays open across
 *   reconnects. A single long-running stdinReader loop decodes NM frames from
 *   stdin into a local AsyncQueue<Message>. On each reconnect, pumpQueueToEdge
 *   drains that queue to the edge. This avoids the double-consumer race that
 *   arises from restarting readFrame(stdin) on each attempt.
 */
import type { Readable, Writable } from 'node:stream'
import { Client } from '../edge/client.js'
import { readFrame, writeFrame, isOversizeFrame } from '../nm/server.js'
import { parse, type Message } from '../edgeproto/edgeproto.js'

// ── Internal async queue ─────────────────────────────────────────────────────

class MsgQueue implements AsyncIterable<Message> {
  readonly #buf: Message[] = []
  readonly #waiters: Array<(v: IteratorResult<Message>) => void> = []
  #done = false

  enqueue(msg: Message): void {
    if (this.#done) return
    if (this.#waiters.length > 0) {
      this.#waiters.shift()!({ value: msg, done: false })
      return
    }
    this.#buf.push(msg)
  }

  close(): void {
    this.#done = true
    for (const w of this.#waiters.splice(0)) {
      w({ value: undefined as unknown as Message, done: true })
    }
  }

  [Symbol.asyncIterator](): AsyncIterator<Message> {
    return {
      next: (): Promise<IteratorResult<Message>> => {
        if (this.#buf.length > 0) {
          return Promise.resolve({ value: this.#buf.shift()!, done: false })
        }
        if (this.#done) {
          return Promise.resolve({ value: undefined as unknown as Message, done: true })
        }
        return new Promise((resolve) => this.#waiters.push(resolve))
      },
    }
  }
}

// ── Runner options ────────────────────────────────────────────────────────────

export interface RunnerOptions {
  client: Client
  stdin: Readable
  stdout: Writable
  /** Initial backoff in ms (default 1000). Doubles per attempt. */
  backoffBase?: number
  /** Maximum backoff cap in ms (default 30000). */
  backoffMax?: number
  /**
   * Maximum consecutive reconnect attempts before throwing. Defaults to
   * unlimited (0 / undefined) — slow-network jitter must not make the Native
   * Host give up and exit. Set a finite cap only for tests.
   */
  maxAttempts?: number
}

// ── Runner ───────────────────────────────────────────────────────────────────

export class Runner {
  readonly #client: Client
  readonly #stdin: Readable
  readonly #stdout: Writable
  readonly #backoffBase: number
  readonly #backoffMax: number
  readonly #maxAttempts: number

  constructor(opts: RunnerOptions) {
    this.#client = opts.client
    this.#stdin = opts.stdin
    this.#stdout = opts.stdout
    this.#backoffBase = opts.backoffBase ?? 1000
    this.#backoffMax = opts.backoffMax ?? 30_000
    // 0 = retry forever (default). A finite cap is only used by tests.
    this.#maxAttempts = opts.maxAttempts ?? 0
  }

  /**
   * Outer reconnect loop. Runs until aborted (clean stop). By default it never
   * gives up on transient failures — it keeps reconnecting with capped
   * exponential backoff so slow/flaky networks self-heal instead of killing the
   * Native Host. A finite maxAttempts cap (tests only) still throws after N
   * consecutive failures.
   *
   * A single stdinReader loop reads NM frames into a MsgQueue and runs for
   * the lifetime of the outer loop. The reconnect inner loop drains the queue
   * to the edge on each attempt.
   */
  async run(signal?: AbortSignal): Promise<void> {
    const stdinQueue = new MsgQueue()
    // Single iterator — shared across all runOnce attempts so we don't
    // re-read items that were already consumed before a disconnect.
    const stdinIter = stdinQueue[Symbol.asyncIterator]()

    // Start the persistent stdin reader. It runs until stdin EOF or outer abort.
    const stdinDonePromise = this.#runStdinReader(stdinQueue, signal)

    let attempt = 0

    while (true) {
      if (signal?.aborted) break

      try {
        await this.#runOnce(stdinIter, signal)
        // Clean exit (signal aborted inside runOnce) — stop.
        break
      } catch (err) {
        if (signal?.aborted) break

        attempt++
        // maxAttempts <= 0 → retry forever (default). A finite cap (tests only)
        // throws after N consecutive failures.
        if (this.#maxAttempts > 0 && attempt >= this.#maxAttempts) {
          stdinQueue.close()
          await stdinDonePromise.catch(() => {})
          throw new Error(
            `runner: ${attempt} consecutive failures — giving up. Last error: ${String(err)}`,
          )
        }

        // Exponential backoff with ±20% jitter
        const base = Math.min(this.#backoffBase * Math.pow(2, attempt - 1), this.#backoffMax)
        const jitter = base * 0.2 * (Math.random() * 2 - 1) // ±20%
        const delay = Math.max(0, base + jitter)

        await sleep(delay, signal)
      }
    }

    stdinQueue.close()
    await stdinDonePromise.catch(() => {})
  }

  /**
   * Persistent stdin reader — decodes NM frames and enqueues parsed Messages.
   * Runs for the full session lifetime (stdin stays open across reconnects).
   */
  async #runStdinReader(queue: MsgQueue, signal?: AbortSignal): Promise<void> {
    // Abort sentinel — resolves when outer signal fires
    const abortedP = new Promise<null>((resolve) => {
      if (signal?.aborted) { resolve(null); return }
      signal?.addEventListener('abort', () => resolve(null), { once: true })
    })

    for (;;) {
      if (signal?.aborted) return

      const frame = await Promise.race([readFrame(this.#stdin), abortedP])
      if (frame === null) { queue.close(); return } // EOF or abort

      // 超大帧(declaredBytes > MAX_FRAME_BYTES,通常是超大截图):body 已被 readFrame
      // drain 丢弃以保持后续帧对齐、连接不断。原帧的 msg_id/in_reply_to 随 body 丢失,
      // 无法精确回 RESULT_TOO_LARGE(后端靠该 action 的 deadline 超时收尾);这里记一条
      // stderr 诊断(stdout 是 NM 协议流、不能污染),不入队、不杀连接。
      if (isOversizeFrame(frame)) {
        process.stderr.write(
          `[bridge] dropped oversize NM frame: declared=${frame.declaredBytes} max=${frame.maxBytes}\n`,
        )
        continue
      }

      const msg = parse(frame.toString())
      if (msg) queue.enqueue(msg)
    }
  }

  /**
   * Single connection attempt.
   * Connects, then races the three concurrent pumps.
   * Returns normally on clean abort; throws on any pump error (triggers reconnect).
   */
  async #runOnce(stdinIter: AsyncIterator<Message>, signal?: AbortSignal): Promise<void> {
    if (signal?.aborted) return

    // Connect (may throw)
    await this.#client.connect()

    // Create a per-connection AbortController so we can cancel pumps
    const innerAc = new AbortController()
    const cleanup = (): void => innerAc.abort()

    // If the outer signal fires, propagate to inner
    signal?.addEventListener('abort', cleanup, { once: true })

    try {
      // Race all three loops — the first to reject wins.
      // clientRun rejects on HeartbeatTimeoutError; pumps reject on I/O errors.
      await Promise.race([
        this.#pumpQueueToEdge(stdinIter, innerAc.signal).finally(() => innerAc.abort()),
        this.#pumpInboundToStdout(innerAc.signal).finally(() => innerAc.abort()),
        this.#client.run(innerAc.signal).finally(() => innerAc.abort()),
      ])
    } finally {
      signal?.removeEventListener('abort', cleanup)
      innerAc.abort() // ensure all loops stop
      await this.#client.close().catch(() => {})
      // Reset client state so it can reconnect on the next attempt
      this.#client.reset()
    }
  }

  /**
   * Pump: shared MsgQueue iterator → stamp session_id → client.send().
   * The iterator is shared across reconnect attempts so no frames are lost.
   * Returns on signal abort. Throws on send failure.
   */
  async #pumpQueueToEdge(iter: AsyncIterator<Message>, signal: AbortSignal): Promise<void> {
    // Sentinel that resolves (to a done result) when signal is aborted
    const abortedP = new Promise<IteratorResult<Message>>((resolve) => {
      if (signal.aborted) {
        resolve({ value: undefined as unknown as Message, done: true })
        return
      }
      signal.addEventListener('abort', () => {
        resolve({ value: undefined as unknown as Message, done: true })
      }, { once: true })
    })

    for (;;) {
      if (signal.aborted) return

      const result = await Promise.race([iter.next(), abortedP])
      if (result.done) return // abort sentinel fired, or queue closed

      const msg = result.value
      // OVERRIDE — do NOT trust whatever the Extension sent.
      msg.session_id = this.#client.sessionId()

      try {
        await this.#client.send(msg)
      } catch {
        if (signal.aborted) return
        throw new Error('runner: failed to send message to edge')
      }
    }
  }

  /**
   * Pump: client.inbound() messages → JSON → NM frames → stdout.
   * Throws if the iterator exhausts without being aborted — that means the
   * remote end dropped the connection unexpectedly, which should trigger reconnect.
   */
  async #pumpInboundToStdout(signal: AbortSignal): Promise<void> {
    for await (const msg of this.#client.inbound()) {
      if (signal.aborted) return
      try {
        await writeFrame(this.#stdout, JSON.stringify(msg))
      } catch {
        if (signal.aborted) return
        throw new Error('runner: failed to write frame to stdout')
      }
    }
    // Iterator exhausted
    if (signal.aborted) return // clean abort — normal
    throw new Error('runner: inbound channel closed unexpectedly (remote disconnect)')
  }
}

// ── helpers ───────────────────────────────────────────────────────────────────

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise<void>((resolve) => {
    if (signal?.aborted) { resolve(); return }
    const h = setTimeout(resolve, ms)
    signal?.addEventListener('abort', () => { clearTimeout(h); resolve() }, { once: true })
  })
}
