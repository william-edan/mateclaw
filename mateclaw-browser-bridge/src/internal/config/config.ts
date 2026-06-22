/**
 * Config loader for MateClaw Browser Bridge.
 *
 * Priority order (highest to lowest):
 *   1. Environment variables (MATECLAW_BRIDGE_*)
 *   2. YAML file at $MATECLAW_HOME/bridge.yaml (or ~/.mateclaw/bridge.yaml)
 *   3. Hardcoded defaults
 */
import { readFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { parse as parseYaml } from 'yaml'

export interface Config {
  /** WebSocket URL for the Control Plane edge endpoint. */
  controlPlaneUrl: string
  /** Bearer token for authenticating to the Control Plane. */
  authToken: string
  /** Reported agent version in hello message. */
  agentVersion: string
  /** Heartbeat interval in milliseconds (overridden by hello.ack). */
  heartbeatIntervalMs: number
}

/** YAML file shape (snake_case keys). */
interface YamlConfig {
  control_plane_url?: string
  auth_token?: string
  agent_version?: string
  heartbeat_interval_ms?: number
}

const DEFAULTS: Config = {
  controlPlaneUrl: 'ws://localhost:18088/api/v1/browser/edge',
  authToken: '',
  agentVersion: 'dev',
  heartbeatIntervalMs: 10000,
}

/** Resolves the MateClaw home directory (env or ~/.mateclaw). */
function resolveHome(): string {
  const envHome = process.env['MATECLAW_HOME']
  if (envHome && envHome.trim() !== '') {
    return envHome.trim()
  }
  return join(homedir(), '.mateclaw')
}

/** Loads the YAML config file; returns null if file doesn't exist. */
async function loadYaml(home: string): Promise<YamlConfig | null> {
  const path = join(home, 'bridge.yaml')
  try {
    const raw = await readFile(path, 'utf8')
    const parsed = parseYaml(raw) as YamlConfig | null
    return parsed ?? null
  } catch (err: unknown) {
    const code = (err as NodeJS.ErrnoException).code
    if (code === 'ENOENT') {
      return null
    }
    throw new Error(`config: failed to read ${path}: ${String(err)}`)
  }
}

/**
 * loadConfig resolves configuration by merging defaults, YAML file, and env vars.
 * Env vars always win.
 *
 * NOTE: This loader treats a missing bridge.yaml and a malformed bridge.yaml
 * differently is NOT its concern — it returns whatever it can and lets the
 * caller decide on the (possibly empty) authToken. For the bounded NO_TOKEN
 * polling path (see {@link tryLoadConfig} / bridge.ts) we need to tell those
 * cases apart so we keep waiting on "no token yet" but fail fast on a real
 * parse/IO error. Use {@link tryLoadConfig} there.
 */
export async function loadConfig(): Promise<Config> {
  const home = resolveHome()
  const yaml = await loadYaml(home)

  // Start from defaults, overlay YAML, then overlay env vars.
  const cfg: Config = { ...DEFAULTS }

  // Layer 2: YAML (if present)
  if (yaml) {
    if (yaml.control_plane_url) cfg.controlPlaneUrl = yaml.control_plane_url
    if (yaml.auth_token) cfg.authToken = yaml.auth_token
    if (yaml.agent_version) cfg.agentVersion = yaml.agent_version
    if (typeof yaml.heartbeat_interval_ms === 'number' && yaml.heartbeat_interval_ms > 0) {
      cfg.heartbeatIntervalMs = yaml.heartbeat_interval_ms
    }
  }

  // Layer 1: env vars (highest priority)
  const cpUrl = process.env['MATECLAW_BRIDGE_CP_URL']
  if (cpUrl && cpUrl.trim() !== '') cfg.controlPlaneUrl = cpUrl.trim()

  const authToken = process.env['MATECLAW_BRIDGE_AUTH_TOKEN']
  if (authToken && authToken.trim() !== '') cfg.authToken = authToken.trim()

  const agentVersion = process.env['MATECLAW_BRIDGE_AGENT_VERSION']
  if (agentVersion && agentVersion.trim() !== '') cfg.agentVersion = agentVersion.trim()

  return cfg
}

/**
 * Outcome of {@link tryLoadConfig} — a pollable, fault-tolerant config read.
 *
 * Three distinct states so the bounded NO_TOKEN poller in bridge.ts can react
 * correctly:
 *   - 'ready'    — a non-empty authToken resolved (env or YAML). `config` set.
 *                  Stop polling, proceed to Client.connect.
 *   - 'no-token' — config read fine but the token is still empty (bridge.yaml
 *                  missing entirely, OR present without an auth_token yet). This
 *                  is the EXPECTED transient on desktop first-launch while the
 *                  SW/desktop shell is still writing bridge.yaml. `config` still
 *                  carries the resolved (token-less) defaults so callers can read
 *                  controlPlaneUrl etc. Keep polling.
 *   - 'error'    — a genuine IO error or YAML parse failure. NOT a transient;
 *                  no amount of re-reading the same broken file will fix it.
 *                  `error` set. Caller may choose to stop early.
 */
export type ConfigReadStatus = 'ready' | 'no-token' | 'error'

export interface ConfigReadResult {
  status: ConfigReadStatus
  /** Present for 'ready' and 'no-token' (token-less defaults overlay). */
  config?: Config
  /** Present for 'error'. */
  error?: Error
}

/**
 * Pollable, non-throwing variant of {@link loadConfig}.
 *
 * Distinguishes "no token yet" (file absent or token field empty — the normal
 * desktop first-launch race where bridge.yaml has not been written yet) from a
 * hard parse/IO error (malformed YAML, permission denied). The bounded NO_TOKEN
 * poller in bridge.ts uses this so it keeps waiting on 'no-token' but can bail
 * out on 'error', instead of the old "no token ⇒ instant self-kill ⇒ SW respawn
 * storm" behaviour.
 *
 * loadConfig() already swallows a missing file (returns null from loadYaml) and
 * only throws on a real read failure / parse failure, so we map any thrown error
 * to 'error' and an empty resolved token to 'no-token'.
 */
export async function tryLoadConfig(): Promise<ConfigReadResult> {
  try {
    const config = await loadConfig()
    if (config.authToken && config.authToken.trim() !== '') {
      return { status: 'ready', config }
    }
    return { status: 'no-token', config }
  } catch (err) {
    return { status: 'error', error: err instanceof Error ? err : new Error(String(err)) }
  }
}
