/**
 * B6 — Chrome Native Messaging stdio codec tests.
 * Tests for readFrame, writeFrame, writeJsonFrame, MAX_FRAME_BYTES.
 *
 * IMPORTANT: Use PassThrough (binary mode) as the source stream for readFrame
 * tests. Readable.from(Buffer) creates an object-mode stream which ignores the
 * n argument to read(), and was the root cause of AbortErrors/timeouts in the
 * prior B6 attempt.
 */
import { describe, it, expect } from 'vitest'
import { PassThrough } from 'node:stream'
import {
  readFrame,
  writeFrame,
  writeJsonFrame,
  MAX_FRAME_BYTES,
  isOversizeFrame,
  parsePingFrame,
  isPingOrPongFrame,
  writePongFrame,
  type OversizeFrame,
} from './server.js'

// ── helpers ───────────────────────────────────────────────────────────────────

/** Build a NM-encoded buffer: 4-byte LE length + payload bytes. */
function encodeFrame(payload: Buffer): Buffer {
  const header = Buffer.alloc(4)
  header.writeUInt32LE(payload.length, 0)
  return Buffer.concat([header, payload])
}

/**
 * Push a Buffer into a PassThrough in binary mode and end it.
 * Returns a readable PassThrough with exactly those bytes.
 */
function makeReadable(data: Buffer): PassThrough {
  const pt = new PassThrough()
  pt.end(data)
  return pt
}

/** Collect all bytes written to a PassThrough stream into a single Buffer. */
async function collectBytes(stream: PassThrough): Promise<Buffer> {
  stream.end()
  const chunks: Buffer[] = []
  for await (const chunk of stream) {
    chunks.push(chunk as Buffer)
  }
  return Buffer.concat(chunks)
}

// ── B6 tests ─────────────────────────────────────────────────────────────────

describe('NM codec — B6', () => {
  it('reads a single frame', async () => {
    const payload = Buffer.from('hello')
    const wire = encodeFrame(payload)
    const stream = makeReadable(wire)
    const result = await readFrame(stream)
    expect(result).not.toBeNull()
    expect(result!.toString()).toBe('hello')
  })

  it('writes a frame', async () => {
    const pt = new PassThrough()
    await writeFrame(pt, Buffer.from('hi'))
    const bytes = await collectBytes(pt)
    // First 4 bytes: length = 2 (LE)
    expect(bytes.readUInt32LE(0)).toBe(2)
    // Remaining bytes: 'hi'
    expect(bytes.slice(4).toString()).toBe('hi')
  })

  it('returns an oversize sentinel (not throw) and stays frame-aligned', async () => {
    const overSize = MAX_FRAME_BYTES + 1
    // 完整超限帧(header + 等量 body),后跟一个正常帧,验证 drainN 排空后仍对齐。
    const big = encodeFrame(Buffer.alloc(overSize, 0x61))
    const normal = encodeFrame(Buffer.from('after'))
    const stream = makeReadable(Buffer.concat([big, normal]))

    const first = await readFrame(stream)
    expect(isOversizeFrame(first)).toBe(true)
    expect((first as OversizeFrame).declaredBytes).toBe(overSize)
    expect((first as OversizeFrame).maxBytes).toBe(MAX_FRAME_BYTES)

    // 超限帧之后的正常帧仍能被正确读出(drainN 对齐成功)。
    const second = await readFrame(stream)
    expect(isOversizeFrame(second)).toBe(false)
    expect(second).not.toBeNull()
    expect((second as Buffer).toString()).toBe('after')
  })

  it('returns null on empty stream (EOF)', async () => {
    const stream = makeReadable(Buffer.alloc(0))
    const result = await readFrame(stream)
    expect(result).toBeNull()
  })

  it('round-trips a JSON frame', async () => {
    const original = { k: 1 }
    const writeSide = new PassThrough()

    // Write the JSON frame
    await writeJsonFrame(writeSide, original)
    writeSide.end()

    // Collect all bytes written, build a readable from them
    const chunks: Buffer[] = []
    for await (const chunk of writeSide) {
      chunks.push(chunk as Buffer)
    }
    const wire = Buffer.concat(chunks)

    // Read back via readFrame using a proper binary-mode stream
    const readSide = makeReadable(wire)
    const frame = await readFrame(readSide)
    expect(frame).not.toBeNull()
    const parsed = JSON.parse(frame!.toString())
    expect(parsed).toEqual(original)
  })
})

// ── 应用层 ping/pong 心跳(契约1)─────────────────────────────────────────────

describe('NM ping/pong helpers', () => {
  it('parsePingFrame 识别 ping 并带回 ts', () => {
    const p = parsePingFrame(JSON.stringify({ kind: 'ping', ts: 1234 }))
    expect(p).not.toBeNull()
    expect(p!.ts).toBe(1234)
  })

  it('parsePingFrame 对非 ping / 坏 JSON / pong 返回 null', () => {
    expect(parsePingFrame(JSON.stringify({ kind: 'pong', ts: 1 }))).toBeNull()
    expect(parsePingFrame(JSON.stringify({ kind: 'action.execute' }))).toBeNull()
    expect(parsePingFrame('not-json')).toBeNull()
    expect(parsePingFrame(JSON.stringify(['ping']))).toBeNull()
  })

  it('isPingOrPongFrame 对 ping 与 pong 都为 true,业务帧为 false', () => {
    expect(isPingOrPongFrame(JSON.stringify({ kind: 'ping', ts: 1 }))).toBe(true)
    expect(isPingOrPongFrame(JSON.stringify({ kind: 'pong', ts: 1 }))).toBe(true)
    expect(isPingOrPongFrame(JSON.stringify({ kind: 'heartbeat' }))).toBe(false)
    expect(isPingOrPongFrame('garbage')).toBe(false)
  })

  it('writePongFrame 原样带回 ts,可被 readFrame 解出', async () => {
    const pt = new PassThrough()
    await writePongFrame(pt, { kind: 'ping', ts: 987654 })
    pt.end()
    const frame = await readFrame(pt)
    expect(frame).not.toBeNull()
    expect(JSON.parse(frame!.toString())).toEqual({ kind: 'pong', ts: 987654 })
  })

  it('writePongFrame 对无 ts 的 ping 回无 ts 的 pong', async () => {
    const pt = new PassThrough()
    await writePongFrame(pt, { kind: 'ping' })
    pt.end()
    const frame = await readFrame(pt)
    expect(JSON.parse(frame!.toString())).toEqual({ kind: 'pong' })
  })
})
