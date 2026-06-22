import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { loadConfig, tryLoadConfig } from './config.js'

function makeTempHome(): string {
  const dir = mkdtempSync(join(tmpdir(), 'mateclaw-test-'))
  return dir
}

describe('config', () => {
  const origEnv = { ...process.env }

  beforeEach(() => {
    // Clear relevant env vars before each test
    delete process.env.MATECLAW_HOME
    delete process.env.MATECLAW_BRIDGE_CP_URL
    delete process.env.MATECLAW_BRIDGE_AUTH_TOKEN
    delete process.env.MATECLAW_BRIDGE_AGENT_VERSION
  })

  afterEach(() => {
    // Restore env
    for (const key of ['MATECLAW_HOME', 'MATECLAW_BRIDGE_CP_URL', 'MATECLAW_BRIDGE_AUTH_TOKEN', 'MATECLAW_BRIDGE_AGENT_VERSION']) {
      if (origEnv[key] !== undefined) {
        process.env[key] = origEnv[key]
      } else {
        delete process.env[key]
      }
    }
  })

  it('returns defaults when no env and no yaml', async () => {
    const home = makeTempHome()
    process.env.MATECLAW_HOME = home

    const cfg = await loadConfig()
    expect(cfg.controlPlaneUrl).toBe('ws://localhost:18088/api/v1/browser/edge')
    expect(cfg.heartbeatIntervalMs).toBe(10000)
    expect(cfg.agentVersion).toBe('dev')
  })

  it('reads from env vars', async () => {
    const home = makeTempHome()
    process.env.MATECLAW_HOME = home
    process.env.MATECLAW_BRIDGE_CP_URL = 'ws://server.example.com/edge'
    process.env.MATECLAW_BRIDGE_AUTH_TOKEN = 'tok-123'

    const cfg = await loadConfig()
    expect(cfg.controlPlaneUrl).toBe('ws://server.example.com/edge')
    expect(cfg.authToken).toBe('tok-123')
  })

  it('reads from YAML file', async () => {
    const home = makeTempHome()
    process.env.MATECLAW_HOME = home

    writeFileSync(
      join(home, 'bridge.yaml'),
      'control_plane_url: ws://yaml.example.com/edge\nauth_token: yaml-tok\n',
      'utf8',
    )

    const cfg = await loadConfig()
    expect(cfg.controlPlaneUrl).toBe('ws://yaml.example.com/edge')
    expect(cfg.authToken).toBe('yaml-tok')
  })

  it('env vars override YAML', async () => {
    const home = makeTempHome()
    process.env.MATECLAW_HOME = home
    process.env.MATECLAW_BRIDGE_CP_URL = 'ws://env.example.com/edge'
    process.env.MATECLAW_BRIDGE_AUTH_TOKEN = 'env-tok'

    writeFileSync(
      join(home, 'bridge.yaml'),
      'control_plane_url: ws://yaml.example.com/edge\n',
      'utf8',
    )

    const cfg = await loadConfig()
    expect(cfg.controlPlaneUrl).toBe('ws://env.example.com/edge')
  })
})

describe('tryLoadConfig — 可轮询的容错读取(契约2)', () => {
  const origEnv = { ...process.env }

  beforeEach(() => {
    delete process.env.MATECLAW_HOME
    delete process.env.MATECLAW_BRIDGE_CP_URL
    delete process.env.MATECLAW_BRIDGE_AUTH_TOKEN
    delete process.env.MATECLAW_BRIDGE_AGENT_VERSION
  })

  afterEach(() => {
    for (const key of ['MATECLAW_HOME', 'MATECLAW_BRIDGE_CP_URL', 'MATECLAW_BRIDGE_AUTH_TOKEN', 'MATECLAW_BRIDGE_AGENT_VERSION']) {
      if (origEnv[key] !== undefined) process.env[key] = origEnv[key]
      else delete process.env[key]
    }
  })

  it('bridge.yaml 不存在 → no-token(可继续轮询)', async () => {
    const home = makeTempHome()
    process.env.MATECLAW_HOME = home
    const res = await tryLoadConfig()
    expect(res.status).toBe('no-token')
    expect(res.config?.authToken).toBe('')
  })

  it('bridge.yaml 存在但无 auth_token → no-token', async () => {
    const home = makeTempHome()
    process.env.MATECLAW_HOME = home
    writeFileSync(join(home, 'bridge.yaml'), 'control_plane_url: ws://x/edge\n', 'utf8')
    const res = await tryLoadConfig()
    expect(res.status).toBe('no-token')
  })

  it('拿到 token → ready', async () => {
    const home = makeTempHome()
    process.env.MATECLAW_HOME = home
    writeFileSync(join(home, 'bridge.yaml'), 'auth_token: tok-xyz\n', 'utf8')
    const res = await tryLoadConfig()
    expect(res.status).toBe('ready')
    expect(res.config?.authToken).toBe('tok-xyz')
  })

  it('坏 YAML → error(不应被当作可重试的 no-token)', async () => {
    const home = makeTempHome()
    process.env.MATECLAW_HOME = home
    // 非法 YAML:未闭合的 flow map,parseYaml 会抛
    writeFileSync(join(home, 'bridge.yaml'), 'auth_token: "unterminated\n: [a, b', 'utf8')
    const res = await tryLoadConfig()
    expect(res.status).toBe('error')
    expect(res.error).toBeInstanceOf(Error)
  })
})
