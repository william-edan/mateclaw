/**
 * bridge — MateClaw Browser Agent Native Host entrypoint.
 *
 * Bridges the Chrome Extension (via Native Messaging over stdio) to the
 * MateClaw Control Plane (via Bearer-over-WSS).
 * See ../../docs/specs/edge-protocol.md for the wire format.
 *
 * Invocation modes
 * ----------------
 * This binary is the `path` target of the Chrome Native Messaging manifest
 * (com.mateclaw.browser_bridge). A manifest `path` cannot carry arguments, so
 * when Chrome launches the host it does NOT pass a `run` subcommand. Instead it
 * passes the calling extension's origin as argv[0] and, on Windows, a
 * `--parent-window=<HWND>` flag, e.g.:
 *
 *     bridge.exe chrome-extension://<id>/ --parent-window=12345
 *
 * Older/explicit invocation still works:
 *
 *     bridge --version       → print version and exit
 *     bridge run             → run the native host explicitly
 *
 * Mode resolution (decideMode):
 *   1. `--version` / `-version`           → 'version'
 *   2. explicit `run` subcommand          → 'run'
 *   3. launched by a browser (argv has a
 *      chrome-extension:// origin, OR
 *      stdin is a pipe / non-TTY)         → 'run'
 *   4. otherwise                          → 'usage'
 */
import { fileURLToPath } from 'node:url'
import { realpathSync } from 'node:fs'
import { createRequire } from 'node:module'
import { loadConfig } from '../internal/config/config.js'
import { Client } from '../internal/edge/client.js'
import { Runner } from '../internal/runner/runner.js'

export const VERSION = '0.1.0'

/** Resolved invocation mode for the bridge process. */
export type Mode = 'version' | 'run' | 'usage'

/**
 * True when the process was launched by a browser as a Native Messaging host.
 *
 * Chromium-based browsers (Chrome, Edge, Brave, …) pass the calling
 * extension's origin as the first program argument, of the form
 * `chrome-extension://<id>/`. We match the scheme prefix rather than a specific
 * id so the same detection works regardless of which extension connects.
 */
export function isNativeMessagingArgv(argv: readonly string[]): boolean {
  return argv.some((a) => a.startsWith('chrome-extension://'))
}

/**
 * Decide how to run, from the program arguments and whether stdin is a TTY.
 *
 * `isStdinTTY` is the only ambient input we take, injected for testability:
 *   - When a human runs `bridge` in a terminal, stdin is a TTY → 'usage'.
 *   - When Chrome spawns the host, stdin is a pipe (non-TTY) → 'run', even if
 *     the chrome-extension:// origin is somehow absent (defensive fallback).
 *
 * `--version` always wins so it is observable in any environment, and an
 * explicit `run` always forces the running path.
 */
export function decideMode(argv: readonly string[], isStdinTTY: boolean): Mode {
  if (argv.includes('--version') || argv.includes('-version')) {
    return 'version'
  }
  if (argv[0] === 'run') {
    return 'run'
  }
  if (isNativeMessagingArgv(argv) || !isStdinTTY) {
    return 'run'
  }
  return 'usage'
}

/**
 * Run the Native Host pipeline: load config, connect to the Control Plane,
 * and pump frames between Chrome (stdio) and the edge WebSocket.
 *
 * Returns 0 on clean shutdown, non-zero on fatal error. Never calls
 * process.exit() itself so callers/tests can decide what to do with the code.
 */
export async function runBridge(): Promise<number> {
  const cfg = await loadConfig()

  if (!cfg.authToken) {
    process.stderr.write(
      'bridge run: MATECLAW_BRIDGE_AUTH_TOKEN or bridge.yaml auth_token is required\n',
    )
    return 1
  }

  const client = new Client({
    url: cfg.controlPlaneUrl,
    authToken: cfg.authToken,
    agentVersion: cfg.agentVersion,
    heartbeatIntervalMs: cfg.heartbeatIntervalMs,
  })

  const runner = new Runner({
    client,
    stdin: process.stdin,
    stdout: process.stdout,
  })

  // Wire SIGINT / SIGTERM to a graceful abort
  const ac = new AbortController()
  const handleSignal = (): void => {
    if (!ac.signal.aborted) ac.abort()
  }
  process.once('SIGINT', handleSignal)
  process.once('SIGTERM', handleSignal)

  try {
    await runner.run(ac.signal)
    return 0
  } catch (err) {
    process.stderr.write(`bridge run: fatal — ${String(err)}\n`)
    return 1
  } finally {
    process.off('SIGINT', handleSignal)
    process.off('SIGTERM', handleSignal)
  }
}

/**
 * Top-level dispatcher. Resolves the mode, performs the side effect, and
 * returns the process exit code. Kept free of process.exit() so it stays unit
 * testable; the entrypoint shim below translates the code into an exit.
 */
export async function main(
  argv: readonly string[],
  isStdinTTY: boolean,
): Promise<number> {
  switch (decideMode(argv, isStdinTTY)) {
    case 'version':
      process.stdout.write(`mateclaw-browser-bridge ${VERSION}\n`)
      return 0
    case 'run':
      return runBridge()
    case 'usage':
      process.stderr.write(
        'bridge: no command (try --version or run)\n',
      )
      return 1
  }
}

/** True when running inside a Node Single-Executable Application (bridge.exe). */
function isSeaBinary(): boolean {
  try {
    // `node:sea` exists on Node 20+. In a normal `node script.js` run isSea()
    // returns false; inside the packaged exe it returns true.
    const sea = createRequire(import.meta.url)('node:sea') as { isSea(): boolean }
    return sea.isSea()
  } catch {
    return false
  }
}

/**
 * True when this module is the program entrypoint (i.e. executed directly via
 * `node dist/cmd/bridge.js` or the packaged bridge.exe), as opposed to being
 * imported by a test or another module. Guarding on this prevents the CLI from
 * running — and calling process.exit() — when the unit tests import it.
 *
 * In a Single-Executable Application there is no argv[1] file on disk, so we
 * detect that first and always run. Otherwise we compare the realpath of the
 * file that started the process (process.argv[1]) with this module's own path.
 */
function isEntrypoint(): boolean {
  if (isSeaBinary()) return true
  try {
    const self = fileURLToPath(import.meta.url)
    const invoked = process.argv[1]
    if (!invoked) return true
    return realpathSync(self) === realpathSync(invoked)
  } catch {
    // Unusual host with no resolvable module URL → assume we are the program.
    return true
  }
}

if (isEntrypoint()) {
  // stdin.isTTY is `true` only for an interactive terminal; it is `undefined`
  // for a pipe (Chrome) — coerce to a strict boolean.
  const isStdinTTY = process.stdin.isTTY === true
  main(process.argv.slice(2), isStdinTTY)
    .then((code) => process.exit(code))
    .catch((err) => {
      process.stderr.write(`bridge: unexpected error — ${String(err)}\n`)
      process.exit(1)
    })
}
