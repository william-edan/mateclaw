/**
 * Chrome Native Messaging stdio codec.
 *
 * Chrome Native Messaging protocol:
 *   - Each message is framed with a 4-byte little-endian length prefix.
 *   - Chrome's documented per-message cap on the EXTENSION side is 1 MB for
 *     messages the extension sends, and effectively unbounded (read in chunks)
 *     for messages the host sends. The host process itself has no Chrome-imposed
 *     cap; the practical ceiling here is the Control Plane WSS buffer (8 MiB,
 *     see WebSocketConfig.java). We size MAX_FRAME_BYTES to match that ceiling
 *     so a large screenshot/snapshot frame round-trips instead of being killed.
 *
 * Exported API:
 *   - MAX_FRAME_BYTES  — the 8 MiB cap constant (aligned with WSS buffer)
 *   - readFrame(stream)          — read one frame; returns null on EOF
 *   - writeFrame(stream, payload) — write one frame
 *   - writeJsonFrame(stream, v)   — convenience: JSON.stringify + writeFrame
 */
import type { Readable, Writable } from 'node:stream'

/**
 * Maximum decoded frame size, in bytes.
 *
 * Aligned with the Control Plane WSS text/binary buffer (8 MiB in
 * WebSocketConfig.java). The extension base64-encodes screenshots up to ~2 MB;
 * with envelope overhead a single frame can comfortably exceed the old 1 MB cap,
 * so 8 MiB keeps the whole pipeline (Extension → Native Host → WSS) consistent.
 */
export const MAX_FRAME_BYTES = 8 * 1024 * 1024

/**
 * Result of {@link readFrame} when a frame's declared length exceeds
 * {@link MAX_FRAME_BYTES}. The oversize body has already been drained from the
 * stream so framing stays aligned; the caller is expected to surface this as a
 * typed error (e.g. an `action.result` with code `RESULT_TOO_LARGE`) rather than
 * tearing down the connection — throwing here would cascade into SESSION_DETACHED.
 */
export interface OversizeFrame {
  readonly oversize: true
  /** Declared (over-limit) length from the 4-byte length prefix. */
  readonly declaredBytes: number
  /** The cap that was exceeded. */
  readonly maxBytes: number
}

/** Narrowing helper: true when a readFrame result is an {@link OversizeFrame}. */
export function isOversizeFrame(v: unknown): v is OversizeFrame {
  return typeof v === 'object' && v !== null && (v as OversizeFrame).oversize === true
}

/**
 * Read exactly n bytes from a Node Readable stream.
 * Returns null on clean EOF (when buf is empty and the stream has ended).
 * Throws on unexpected EOF mid-frame.
 *
 * Uses the "wait for 'readable' or 'end'" pattern — does NOT use
 * AbortController / signal, which caused AbortErrors in prior attempts.
 *
 * Key subtlety: Readable.from(buffer) may have already emitted 'end' before
 * our listener is attached. We guard this by checking stream.readableEnded
 * synchronously after a null read.
 */
async function readN(stream: Readable, n: number): Promise<Buffer | null> {
  let buf = Buffer.alloc(0)

  while (buf.length < n) {
    const chunk = stream.read(n - buf.length) as Buffer | null

    if (chunk === null) {
      // Check if stream already ended (synchronous check first — avoids missing
      // the 'end' event that fires before our listener is attached).
      if (stream.readableEnded) {
        if (buf.length === 0) return null
        throw new Error(`nm: unexpected EOF mid-frame (got ${buf.length}, need ${n})`)
      }

      // No data available yet — wait for the stream to have data or to end.
      const ended = await new Promise<boolean>((resolve) => {
        const onReadable = (): void => {
          cleanup()
          resolve(false)
        }
        const onEnd = (): void => {
          cleanup()
          resolve(true)
        }
        const cleanup = (): void => {
          stream.off('readable', onReadable)
          stream.off('end', onEnd)
        }
        stream.once('readable', onReadable)
        stream.once('end', onEnd)
      })

      if (ended && buf.length === 0) return null
      if (ended) throw new Error(`nm: unexpected EOF mid-frame (got ${buf.length}, need ${n})`)
      // 'readable' fired — loop again and try stream.read()
      continue
    }

    buf = Buffer.concat([buf, chunk])
  }

  return buf
}

/**
 * Discard exactly n bytes from a Readable stream without buffering them all.
 * Used to drain an over-limit frame body so the next length prefix stays aligned.
 * Returns false on unexpected EOF mid-drain (nothing left to keep reading).
 */
async function drainN(stream: Readable, n: number): Promise<boolean> {
  let remaining = n

  while (remaining > 0) {
    // Read in capped slices so a multi-MB oversize body never lands in one Buffer.
    const want = Math.min(remaining, 64 * 1024)
    const chunk = stream.read(want) as Buffer | null

    if (chunk === null) {
      if (stream.readableEnded) return false

      const ended = await new Promise<boolean>((resolve) => {
        const onReadable = (): void => {
          cleanup()
          resolve(false)
        }
        const onEnd = (): void => {
          cleanup()
          resolve(true)
        }
        const cleanup = (): void => {
          stream.off('readable', onReadable)
          stream.off('end', onEnd)
        }
        stream.once('readable', onReadable)
        stream.once('end', onEnd)
      })

      if (ended) return false
      continue
    }

    remaining -= chunk.length
  }

  return true
}

/**
 * Read one Native Messaging frame from a Readable stream.
 *
 * @returns
 *   - a {@link Buffer} with the frame payload, or
 *   - `null` on clean EOF, or
 *   - an {@link OversizeFrame} sentinel when the declared length exceeds
 *     {@link MAX_FRAME_BYTES}. In that case the over-limit body is drained from
 *     the stream so the next frame stays aligned, and the caller MUST translate
 *     the sentinel into a typed error rather than throwing — throwing kills the
 *     connection and cascades into SESSION_DETACHED.
 * @throws  Error only on unexpected EOF mid-frame (genuine stream corruption).
 */
export async function readFrame(
  stream: Readable,
): Promise<Buffer | OversizeFrame | null> {
  const header = await readN(stream, 4)
  if (header === null) return null

  const length = header.readUInt32LE(0)

  if (length === 0) return Buffer.alloc(0)

  if (length > MAX_FRAME_BYTES) {
    // Drain the over-limit body so the next length prefix stays aligned, then
    // hand back a structured sentinel instead of throwing. If the drain hits a
    // genuine mid-frame EOF the stream is unrecoverable, so surface that as the
    // usual hard error.
    const drained = await drainN(stream, length)
    if (!drained) {
      throw new Error(
        `nm: unexpected EOF while draining over-size frame (declared ${length} bytes)`,
      )
    }
    return { oversize: true, declaredBytes: length, maxBytes: MAX_FRAME_BYTES }
  }

  return readN(stream, length)
}

/**
 * Write one Native Messaging frame to a Writable stream.
 * Prepends a 4-byte little-endian length prefix.
 */
export function writeFrame(stream: Writable, payload: Buffer | string): Promise<void> {
  const data: Buffer = typeof payload === 'string' ? Buffer.from(payload) : payload
  const header = Buffer.alloc(4)
  header.writeUInt32LE(data.length, 0)
  const frame = Buffer.concat([header, data])

  return new Promise<void>((resolve, reject) => {
    stream.write(frame, (err) => {
      if (err) reject(err)
      else resolve()
    })
  })
}

/**
 * Convenience: JSON-stringify a value and write it as a Native Messaging frame.
 */
export function writeJsonFrame(stream: Writable, v: unknown): Promise<void> {
  return writeFrame(stream, JSON.stringify(v))
}

// ── 应用层心跳(扩展 ⇄ bridge 段保活)──────────────────────────────────────────
//
// 跨组契约1:扩展(组A)每 ~20s 经 port.postMessage 发 {kind:'ping', ts}; bridge(组B)
// 从 native stdin 收到 ping 立即经 stdout 回 {kind:'pong', ts}(原样带回 ts)。
// 目的:扩展收到 inbound NM 消息(pong)才是 Chrome 认可的 SW idle 续期事件,纯
// chrome.* API 空转不算。这些帧【只用于扩展⇄bridge 段】,不得转发给后端 WSS。
//
// 这里用最小依赖的方式识别/构造 ping/pong:直接看 JSON 的 kind 字段,避免把 nm 编解码
// 层耦合到完整的 edgeproto 信封校验(ping/pong 是裸 {kind, ts},不带 v/msg_id 等)。

/** 应用层心跳帧的最小形状(裸帧:扩展只发 kind+ts,不走 edgeproto 信封)。 */
export interface PingPongFrame {
  kind: 'ping' | 'pong'
  /** 发起方时间戳(ms);pong 必须原样带回 ping 的 ts。 */
  ts?: number
}

/**
 * 解析一个 NM 帧 body,判断它是否是应用层 ping 帧。
 * 返回解析出的 PingPongFrame(便于回 pong 时取 ts),否则返回 null。
 *
 * 容错:JSON 解析失败 / 非对象 / kind 非 'ping' 一律返回 null —— 业务 edge 帧(action.*
 * 等)绝不会被误判为 ping(它们 kind 不为 'ping')。
 */
export function parsePingFrame(body: Buffer | string): PingPongFrame | null {
  let obj: unknown
  try {
    obj = JSON.parse(typeof body === 'string' ? body : body.toString())
  } catch {
    return null
  }
  if (typeof obj !== 'object' || obj === null) return null
  const o = obj as Record<string, unknown>
  if (o['kind'] !== 'ping') return null
  return { kind: 'ping', ts: typeof o['ts'] === 'number' ? o['ts'] : undefined }
}

/**
 * 判断一个 NM 帧 body 是否是 ping 或 pong(任一方向的应用层心跳)。
 * 调用方据此【过滤】这两类帧,避免把它们转发给后端 WSS(契约1)。
 */
export function isPingOrPongFrame(body: Buffer | string): boolean {
  let obj: unknown
  try {
    obj = JSON.parse(typeof body === 'string' ? body : body.toString())
  } catch {
    return false
  }
  if (typeof obj !== 'object' || obj === null) return false
  const k = (obj as Record<string, unknown>)['kind']
  return k === 'ping' || k === 'pong'
}

/**
 * 针对收到的 ping 帧,经 stdout(或任意 Writable)写回一帧 pong,原样带回 ts。
 * 不带 ts 的 ping 回不带 ts 的 pong(向后兼容)。
 */
export function writePongFrame(stream: Writable, ping: PingPongFrame): Promise<void> {
  const pong: PingPongFrame =
    typeof ping.ts === 'number' ? { kind: 'pong', ts: ping.ts } : { kind: 'pong' }
  return writeJsonFrame(stream, pong)
}
