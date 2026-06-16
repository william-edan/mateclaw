/**
 * Unit tests for the bridge entrypoint mode resolution.
 *
 * The critical behaviour: when Chrome launches the Native Messaging host it
 * does NOT pass a `run` subcommand — it passes the extension origin and (on
 * Windows) a --parent-window flag, and connects stdin as a pipe. The bridge
 * must still enter the running path in that case.
 */
import { describe, it, expect } from 'vitest'
import { decideMode, isNativeMessagingArgv } from './bridge.js'

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
})
