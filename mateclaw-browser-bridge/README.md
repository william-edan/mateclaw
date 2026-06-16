# MateClaw Browser Bridge (Native Host)

Per-user-machine daemon that bridges the MateClaw Chrome Extension
(over Chrome Native Messaging / stdio) to the MateClaw Control Plane
(over Bearer-authenticated WSS).

Phase 1 scope: hello / heartbeat / ping pipeline.

## Build

    pnpm build        # compile TypeScript → dist/
    pnpm test         # run unit tests
    pnpm dev          # tsx watch (for development)

## Package a single-file executable (bridge.exe)

Chrome launches a Native Messaging host by the `path` in its manifest, and that
path **cannot carry arguments**. So we ship a self-contained executable that
runs the bridge with no subcommand:

    pnpm build:exe     # → dist/cmd/bridge.exe  (Windows x64, single file)
    pnpm build:bundle  # → dist/bridge.bundle.cjs  (JS bundle only, no exe)

`build:exe` runs three steps (see `scripts/build-exe.mjs`):

1. **esbuild** bundles `src/cmd/bridge.ts` (plus `ws` and `yaml`) into one CJS file.
2. **Node SEA** (`--experimental-sea-config`) turns that bundle into a SEA blob.
3. **postject** injects the blob into a copy of the current `node.exe`.

Output path — **this is the cross-module contract**:

    mateclaw-browser-bridge/dist/cmd/bridge.exe

`mateclaw-desktop/scripts/prepare-resources.mjs` copies exactly this file into the
desktop package's `resources/bridge/bridge.exe`. Do not rename or relocate it.

Notes:
- Build `bridge.exe` **on Windows x64** — SEA embeds the host `node.exe`, so the
  artifact targets the build platform. (`build:exe` warns if run elsewhere.)
- postject prints `warning: The signature seems corrupted!` while it strips
  `node.exe`'s Authenticode signature. That is expected; the produced exe is
  unsigned. Re-sign downstream if your distribution requires it.

## Run

A native host is normally started by the browser, not by hand. The entrypoint
auto-detects how it was launched (`src/cmd/bridge.ts` → `decideMode`):

- **`--version`** → print version and exit (works in any environment).
- **`run`** subcommand → run the host explicitly.
- **launched by a browser** → run. Chrome passes the extension origin
  (`chrome-extension://<id>/`) as the first argument and connects stdin as a
  pipe; either signal (origin arg, or non-TTY stdin) selects the running path
  even though no `run` argument is present.
- otherwise (bare interactive terminal) → print usage.

Manual invocations:

    node dist/cmd/bridge.js --version
    node dist/cmd/bridge.js run
    dist/cmd/bridge.exe --version
    dist/cmd/bridge.exe run

### Configuration

Credentials are read from `<home>/.mateclaw/bridge.yaml` (see
`src/internal/config/config.ts`), overridable by env vars:

```yaml
control_plane_url: ws://localhost:18088/api/v1/browser/edge
auth_token: <PAT with scope browser:edge>
```

Env overrides: `MATECLAW_BRIDGE_CP_URL`, `MATECLAW_BRIDGE_AUTH_TOKEN`,
`MATECLAW_BRIDGE_AGENT_VERSION`, and `MATECLAW_HOME` (config directory).
