/**
 * Unit tests for the bridge entrypoint mode resolution.
 *
 * The critical behaviour: when Chrome launches the Native Messaging host it
 * does NOT pass a `run` subcommand — it passes the extension origin and (on
 * Windows) a --parent-window flag, and connects stdin as a pipe. The bridge
 * must still enter the running path in that case.
 */
import { describe, it, expect } from 'vitest'
import { PassThrough } from 'node:stream'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  decideMode,
  isNativeMessagingArgv,
  isResidentArgv,
  waitForToken,
  pumpStdinWithPingFilter,
} from './bridge.js'
import { readFrame, writeFrame } from '../internal/nm/server.js'

const EXT = 'chrome-extension://bjdhmojdiahokgfcaahphcjgcnffbonf/'

describe('isNativeMessagingArgv', () => {
  it('detects the chrome-extension origin Chrome passes as argv[0]', () => {
    expect(isNativeMessagingArgv([EXT])).toBe(true)
    expect(isNativeMessagingArgv([EXT, '--parent-window=12345'])).toBe(true)
  })

  it('is false for ordinary CLI args', () => {
    expect(isNativeMessagingArgv([])).toBe(false)
    expect(isNativeMessagingArgv(['run'])).toBe(false)
    expect(isNativeMessagingArgv(['--version'])).toBe(false)
  })
})

describe('decideMode', () => {
  // ── The headline fix: launched by Chrome with no "run" ────────────────────

  it('runs when launched by Chrome (origin arg present), even without "run"', () => {
    // stdin is a pipe under Chrome → isStdinTTY=false, but origin alone suffices
    expect(decideMode([EXT, '--parent-window=12345'], false)).toBe('run')
  })

  it('runs when stdin is a pipe even if the origin arg is somehow absent', () => {
    // Defensive fallback: a non-TTY stdin with no args means we were piped into.
    expect(decideMode([], false)).toBe('run')
  })

  // ── Explicit invocations still work ───────────────────────────────────────

  it('runs on an explicit "run" subcommand', () => {
    expect(decideMode(['run'], true)).toBe('run')
  })

  it('prints version on --version (and -version), in any environment', () => {
    expect(decideMode(['--version'], true)).toBe('version')
    expect(decideMode(['-version'], false)).toBe('version')
  })

  it('--version wins over a Chrome-style launch', () => {
    expect(decideMode([EXT, '--version'], false)).toBe('version')
  })

  // ── Interactive human with no args → usage ────────────────────────────────

  it('shows usage for a bare interactive terminal invocation', () => {
    expect(decideMode([], true)).toBe('usage')
  })

  // ── 常驻模式(连接根治 / feature-flag 默认 off)──────────────────────────────

  it('resolves resident on `run --resident`', () => {
    expect(decideMode(['run', '--resident'], false)).toBe('resident')
  })

  it('resident wins even with a Chrome-style origin arg present', () => {
    // 桌面壳显式拉常驻时仍可能带其它参数;--resident 始终优先于 NM 检测。
    expect(decideMode([EXT, '--resident'], false)).toBe('resident')
  })

  it('--version still wins over --resident', () => {
    expect(decideMode(['run', '--resident', '--version'], false)).toBe('version')
  })

  it('without --resident a Chrome launch is still NM run (feature-flag off = 旧行为)', () => {
    // 契约4:桌面不传 --resident → Chrome 拉起原生 host 走原 NM 路径,行为与今天一致。
    expect(decideMode([EXT, '--parent-window=1'], false)).toBe('run')
  })
})

describe('isResidentArgv', () => {
  it('detects --resident anywhere in argv', () => {
    expect(isResidentArgv(['run', '--resident'])).toBe(true)
    expect(isResidentArgv(['--resident'])).toBe(true)
  })
  it('is false without --resident (default off)', () => {
    expect(isResidentArgv([])).toBe(false)
    expect(isResidentArgv(['run'])).toBe(false)
    expect(isResidentArgv([EXT, '--parent-window=1'])).toBe(false)
  })
})

// ── NO_TOKEN 有界轮询(契约2)──────────────────────────────────────────────────

describe('waitForToken — 有界轮询', () => {
  const origEnv = { ...process.env }

  function tmpHome(): string {
    return mkdtempSync(join(tmpdir(), 'mateclaw-bridge-'))
  }

  function restoreEnv(): void {
    for (const key of ['MATECLAW_HOME', 'MATECLAW_BRIDGE_AUTH_TOKEN']) {
      if (origEnv[key] !== undefined) process.env[key] = origEnv[key]
      else delete process.env[key]
    }
  }

  it('token 已就绪 → 立即返回 Config,不轮询', async () => {
    process.env.MATECLAW_HOME = tmpHome()
    process.env.MATECLAW_BRIDGE_AUTH_TOKEN = 'ready-tok'
    let slept = 0
    const cfg = await waitForToken({
      timeoutMs: 5000,
      intervalMs: 1000,
      sleep: async () => { slept++ },
    })
    expect(cfg?.authToken).toBe('ready-tok')
    expect(slept).toBe(0)
    delete process.env.MATECLAW_BRIDGE_AUTH_TOKEN
    restoreEnv()
  })

  it('先无 token、轮询期间被写入 → 接续返回 Config', async () => {
    const home = tmpHome()
    process.env.MATECLAW_HOME = home
    delete process.env.MATECLAW_BRIDGE_AUTH_TOKEN

    let polls = 0
    const cfg = await waitForToken({
      timeoutMs: 10_000,
      intervalMs: 10,
      now: () => 0, // 冻结时间,确保不会因超时退出
      sleep: async () => {
        polls++
        if (polls === 3) {
          // 模拟桌面壳第三次轮询前写出 bridge.yaml
          writeFileSync(join(home, 'bridge.yaml'), 'auth_token: late-tok\n', 'utf8')
        }
      },
    })
    expect(cfg?.authToken).toBe('late-tok')
    restoreEnv()
  })

  it('超时仍无 token → 返回 null(调用方据此发 NO_TOKEN)', async () => {
    process.env.MATECLAW_HOME = tmpHome()
    delete process.env.MATECLAW_BRIDGE_AUTH_TOKEN

    // now 第一次返回 0(算 deadline),之后返回超过 deadline 的值 → 一轮即超时
    let t = 0
    const cfg = await waitForToken({
      timeoutMs: 1000,
      intervalMs: 1,
      now: () => { const v = t; t = 5000; return v },
      sleep: async () => {},
    })
    expect(cfg).toBeNull()
    restoreEnv()
  })
})

// ── ping/pong 拦截过滤器(契约1)──────────────────────────────────────────────

describe('pumpStdinWithPingFilter — 应用层心跳拦截', () => {
  /** 把若干 JSON 对象按 NM 帧写进一个可读 PassThrough。 */
  async function feed(...objs: unknown[]): Promise<PassThrough> {
    const src = new PassThrough()
    for (const o of objs) await writeFrame(src, JSON.stringify(o))
    src.end()
    return src
  }

  it('ping 被就地回 pong(带回 ts)且不下发给 Runner', async () => {
    const stdin = await feed({ kind: 'ping', ts: 42 })
    const stdout = new PassThrough()
    const out = new PassThrough()

    await pumpStdinWithPingFilter(stdin, stdout, out)

    // stdout 收到 pong
    stdout.end()
    const pongFrame = await readFrame(stdout)
    expect(JSON.parse(pongFrame!.toString())).toEqual({ kind: 'pong', ts: 42 })

    // out(给 Runner 的流)不应有任何 ping 帧 —— 已 end,直接 EOF
    const forwarded = await readFrame(out)
    expect(forwarded).toBeNull()
  })

  it('业务帧原样转发给 Runner,ping 被过滤掉', async () => {
    const stdin = await feed(
      { kind: 'ping', ts: 1 },
      { kind: 'action.execute', payload: { a: 1 } },
      { kind: 'ping', ts: 2 },
    )
    const stdout = new PassThrough()
    const out = new PassThrough()

    await pumpStdinWithPingFilter(stdin, stdout, out)
    out.end()

    // out 里只剩那一帧业务帧
    const f1 = await readFrame(out)
    expect(JSON.parse(f1!.toString())).toEqual({ kind: 'action.execute', payload: { a: 1 } })
    const f2 = await readFrame(out)
    expect(f2).toBeNull()

    // stdout 里有两帧 pong
    stdout.end()
    const p1 = await readFrame(stdout)
    expect(JSON.parse(p1!.toString())).toEqual({ kind: 'pong', ts: 1 })
    const p2 = await readFrame(stdout)
    expect(JSON.parse(p2!.toString())).toEqual({ kind: 'pong', ts: 2 })
  })

  it('stdin EOF → end 掉下游 out(Runner 据此干净收尾)', async () => {
    const stdin = await feed() // 空 → 立即 EOF
    const stdout = new PassThrough()
    const out = new PassThrough()
    await pumpStdinWithPingFilter(stdin, stdout, out)
    const f = await readFrame(out)
    expect(f).toBeNull()
  })
})
