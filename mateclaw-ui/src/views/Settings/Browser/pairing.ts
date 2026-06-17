// Pure, framework-free helpers for the Browser Pairing card. Kept out of the
// Vue SFC so the wire-protocol logic (extension messaging, WS-URL derivation,
// the connect state machine) is unit-testable with a mocked `chrome.runtime`
// and a mocked HTTP layer — no component render required.
//
// Wire shapes are frozen in docs/specs/phase-3.1-contract.md (§0 EXTENSION_ID,
// §2 ping/pair/unpair messages, §1 the WS endpoint path). Obey literally.

/**
 * The unpacked extension pins a stable public key, so its ID is deterministic
 * across machines (contract §0). Overridable at build time via
 * VITE_MATECLAW_EXTENSION_ID for a future Chrome Web Store listing.
 */
export const EXTENSION_ID =
  import.meta.env.VITE_MATECLAW_EXTENSION_ID || 'bjdhmojdiahokgfcaahphcjgcnffbonf'

// ---------------------------------------------------------------------------
// Extension message shapes (contract §2) — what we SEND and what we RECEIVE.
// ---------------------------------------------------------------------------

export interface PingResponse {
  alive: true
  /** User-facing device label the extension persisted, or null if unnamed. */
  deviceName: string | null
  /** True when the extension's DirectBridgeClient holds an OPEN socket with a
   *  session_id. This is the value the UI polls after pairing. */
  connected: boolean
  deviceId: string
}

export interface PairOkResponse {
  ok: true
}
export interface PairErrorResponse {
  ok: false
  error: string
}
export type PairResponse = PairOkResponse | PairErrorResponse

export type ExtensionMessage =
  | { type: 'ping' }
  | { type: 'pair'; pat: string; serverUrl: string; deviceName?: string }
  | { type: 'unpair' }
  | { type: 'reconnect' }

// Minimal structural typing for the slice of the `chrome` API we touch. The
// admin UI may run in Firefox or a browser without the extension installed, so
// every field is optional and every access is guarded.
interface ChromeRuntimeLike {
  runtime?: {
    lastError?: unknown
    sendMessage?: (
      extensionId: string,
      message: unknown,
      callback: (response: unknown) => void,
    ) => void
  }
}

/**
 * Send a message to the extension via `chrome.runtime.sendMessage`, resolving
 * to the typed response or `null` when the extension is unreachable.
 *
 * Resolves `null` (never rejects / throws) in every degraded case:
 *  - `chrome` undefined (Firefox / non-Chromium, or extension APIs unavailable)
 *  - `chrome.runtime.sendMessage` missing
 *  - `chrome.runtime.lastError` set in the callback (extension not installed /
 *    not listening / origin not whitelisted)
 *  - `sendMessage` throws synchronously
 *
 * `chromeLike` defaults to the real `window.chrome`; tests inject a fake.
 */
export function sendToExtension<T>(
  msg: ExtensionMessage,
  chromeLike: ChromeRuntimeLike | undefined = (globalThis as { chrome?: ChromeRuntimeLike })
    .chrome,
): Promise<T | null> {
  return new Promise((resolve) => {
    const c = chromeLike
    if (!c?.runtime?.sendMessage) {
      // Not Chrome, or extension messaging APIs unavailable.
      resolve(null)
      return
    }
    try {
      c.runtime.sendMessage(EXTENSION_ID, msg, (resp: unknown) => {
        // lastError must be read inside the callback; touching it is what
        // suppresses Chrome's "Unchecked runtime.lastError" console noise.
        if (c.runtime?.lastError) {
          resolve(null)
          return
        }
        resolve((resp as T) ?? null)
      })
    } catch {
      resolve(null)
    }
  })
}

/**
 * Derive the extension's WebSocket endpoint from a page origin (contract §1):
 * keep host:port, swap the scheme (`https→wss`, `http→ws`), set the path to
 * `/api/v1/browser/edge`. Defaults to the live `location.origin`.
 *
 * @example deriveWsUrl('https://mateclaw.example.com') === 'wss://mateclaw.example.com/api/v1/browser/edge'
 * @example deriveWsUrl('http://localhost:18088')       === 'ws://localhost:18088/api/v1/browser/edge'
 * @example deriveWsUrl('http://localhost:5173')        === 'ws://localhost:18088/api/v1/browser/edge'
 * @example deriveWsUrl('http://127.0.0.1:5173')        === 'ws://127.0.0.1:18088/api/v1/browser/edge'
 */
export function deriveWsUrl(origin: string = location.origin): string {
  const u = new URL(origin)
  if ((u.hostname === 'localhost' || u.hostname === '127.0.0.1') && u.port === '5173') {
    u.port = '18088'
  }
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:'
  u.pathname = '/api/v1/browser/edge'
  u.search = ''
  u.hash = ''
  // URL serializes a ws(s) origin with a trailing slash on an empty path; we
  // set an explicit path so toString() yields exactly the endpoint.
  return u.toString()
}

/**
 * A reasonable default device label when the user doesn't supply one. Reads
 * the platform off the UA where available; falls back to a generic string.
 * Never throws (navigator may be partially stubbed under test).
 */
export function defaultDeviceName(): string {
  try {
    const nav = (globalThis as { navigator?: Navigator }).navigator
    const ua = nav?.userAgent ?? ''
    let os = 'Browser'
    if (/Windows/i.test(ua)) os = 'Windows'
    else if (/Mac OS X|Macintosh/i.test(ua)) os = 'macOS'
    else if (/Linux/i.test(ua)) os = 'Linux'
    else if (/Android/i.test(ua)) os = 'Android'
    else if (/iPhone|iPad|iPod/i.test(ua)) os = 'iOS'
    let browser = ''
    if (/Edg\//i.test(ua)) browser = 'Edge'
    else if (/Chrome\//i.test(ua)) browser = 'Chrome'
    else if (/Firefox\//i.test(ua)) browser = 'Firefox'
    return browser ? `${browser} on ${os}` : os
  } catch {
    return 'Browser'
  }
}

// ---------------------------------------------------------------------------
// Connect state machine (mint → pair → poll). Pure: no Vue, no timers of its
// own — the caller supplies the async primitives so tests stay deterministic.
// ---------------------------------------------------------------------------

/** UI status pill states. */
export type PairingStatus =
  | 'unknown' // before the first ping resolves
  | 'not-detected' // ping returned null → extension absent / not listening
  | 'detected' // alive, but no open socket yet (grey→yellow)
  | 'connected' // alive + connected:true (green)

export interface ConnectDeps {
  /** Mint a browser-scoped PAT (contract §3). */
  mintToken: (deviceName?: string) => Promise<{ token: string; tokenId: string; expiresAt: string }>
  /** Best-effort revoke of a minted PAT (contract §3 companion). */
  revokeToken: (tokenId: string) => Promise<unknown>
  /** Push credentials into the extension (contract §2 `pair`). */
  pair: (pat: string, serverUrl: string, deviceName: string) => Promise<PairResponse | null>
  /** Probe extension liveness/connection (contract §2 `ping`). */
  ping: () => Promise<PingResponse | null>
  /** Build the WS URL — injectable for tests; defaults to deriveWsUrl(). */
  wsUrl?: () => string
  /** Sleep between polls — injectable so tests don't wait on real time. */
  sleep?: (ms: number) => Promise<void>
}

export interface ConnectOptions {
  deviceName: string
  /** Max ping polls awaiting connected:true. Contract suggests ~10. */
  maxPolls?: number
  /** Delay between polls, ms. Contract suggests ~1s. */
  pollIntervalMs?: number
}

export type ConnectResult =
  | { ok: true; tokenId: string; expiresAt: string }
  | { ok: false; error: string; reason: 'mint' | 'pair' | 'timeout' }

const realSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/**
 * Drive the full one-click connect:
 *  1. mint a PAT,
 *  2. push `{type:'pair', pat, serverUrl, deviceName}` to the extension,
 *  3. poll `ping` until `connected:true` (up to maxPolls).
 *
 * On any failure after the PAT is minted (pair rejected, or never connects),
 * the minted PAT is revoked best-effort so we never leak a live credential.
 * Returns a discriminated result the component maps to a status + toast.
 */
export async function runConnect(
  deps: ConnectDeps,
  opts: ConnectOptions,
): Promise<ConnectResult> {
  const sleep = deps.sleep ?? realSleep
  const wsUrl = (deps.wsUrl ?? deriveWsUrl)()
  const maxPolls = opts.maxPolls ?? 10
  const pollIntervalMs = opts.pollIntervalMs ?? 1000

  // 1. Mint.
  let tokenId: string
  let expiresAt: string
  let token: string
  try {
    const minted = await deps.mintToken(opts.deviceName)
    token = minted.token
    tokenId = minted.tokenId
    expiresAt = minted.expiresAt
  } catch (e) {
    return { ok: false, reason: 'mint', error: errMsg(e) }
  }

  // 2. Pair. From here on, any failure path revokes the freshly minted PAT.
  const pairResp = await deps.pair(token, wsUrl, opts.deviceName)
  if (!pairResp || pairResp.ok !== true) {
    await safeRevoke(deps, tokenId)
    const error = pairResp && pairResp.ok === false ? pairResp.error : 'extension-unreachable'
    return { ok: false, reason: 'pair', error }
  }

  // 3. Poll for connected:true. pair acked only "stored + connect initiated".
  for (let i = 0; i < maxPolls; i++) {
    await sleep(pollIntervalMs)
    const p = await deps.ping()
    if (p?.connected) {
      return { ok: true, tokenId, expiresAt }
    }
  }

  await safeRevoke(deps, tokenId)
  return { ok: false, reason: 'timeout', error: 'never-connected' }
}

async function safeRevoke(deps: ConnectDeps, tokenId: string): Promise<void> {
  try {
    await deps.revokeToken(tokenId)
  } catch {
    /* best-effort: an orphaned PAT is recoverable, a thrown error here is not */
  }
}

function errMsg(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}

/** Map a ping result to the pill status. Centralised so the SFC and the test
 *  agree on the grey/yellow/green decision. */
export function statusFromPing(ping: PingResponse | null): PairingStatus {
  if (!ping) return 'not-detected'
  return ping.connected ? 'connected' : 'detected'
}

/** localStorage key holding the tokenId of the active pairing, so [Disconnect]
 *  can revoke it across reloads. */
export const PAIRING_TOKEN_KEY = 'mc-browser-pairing-token-id'

// ---------------------------------------------------------------------------
// 发起获客前“自动连接”：ping 唤醒(可能已休眠的)扩展 SW → 已连直接返回；未连则触发
// reconnect 并轮询 ping 直到 connected:true 或超时。供获客页 submit 前调用，避免 NO_SESSION。
// ---------------------------------------------------------------------------

export interface EnsureConnectedDeps {
  ping?: () => Promise<PingResponse | null>
  reconnect?: () => Promise<{ ok?: boolean; connected?: boolean } | null>
  /** Returns true when the server reports a live edge session for this user —
   *  the reliable source of truth for the Native-Messaging path, where the page
   *  (especially INSIDE the desktop client) cannot ping the Chrome-hosted
   *  extension at all. Defaults to a no-op (false) so ping-only callers keep
   *  their old behaviour. */
  sessionConnected?: () => Promise<boolean>
  sleep?: (ms: number) => Promise<void>
}

export interface EnsureConnectedResult {
  connected: boolean
  reason?: 'not-detected' | 'timeout'
}

export async function ensureExtensionConnected(
  deps: EnsureConnectedDeps = {},
  opts: { maxPolls?: number; pollIntervalMs?: number } = {},
): Promise<EnsureConnectedResult> {
  const ping = deps.ping ?? (() => sendToExtension<PingResponse>({ type: 'ping' }))
  const reconnect =
    deps.reconnect ?? (() => sendToExtension<{ ok?: boolean; connected?: boolean }>({ type: 'reconnect' }))
  const sessionConnected = deps.sessionConnected ?? (() => Promise.resolve(false))
  const sleep = deps.sleep ?? realSleep
  const maxPolls = opts.maxPolls ?? 8
  const pollIntervalMs = opts.pollIntervalMs ?? 700

  // 0. Server edge session is the reliable truth for Native Messaging: the
  //    extension connects to the server directly, and the desktop client can't
  //    ping the Chrome-hosted extension at all. If a session is already live,
  //    we're done — no ping needed.
  if (await sessionConnected()) return { connected: true }

  // 1. ping —— 唤醒可能已休眠的扩展 SW（best-effort；desktop 客户端内会返回 null）。
  const p = await ping()
  if (p?.connected) return { connected: true }

  // 2. 未连 → 触发扩展重连（有配对走 direct，否则 native“装好即连”）。
  await reconnect()

  // 3. 轮询：server session 或 ping.connected 任一为真即视为已连。
  for (let i = 0; i < maxPolls; i++) {
    await sleep(pollIntervalMs)
    if (await sessionConnected()) return { connected: true }
    const pp = await ping()
    if (pp?.connected) return { connected: true }
  }
  // 既无 server session、又 ping 不到扩展 → not-detected；ping 到了但没连上 → timeout。
  return { connected: false, reason: p === null ? 'not-detected' : 'timeout' }
}
