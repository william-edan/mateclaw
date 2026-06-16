/**
 * build-exe.mjs — produce a single-file `bridge.exe` (Windows x64) that Chrome
 * can launch directly as a Native Messaging host.
 *
 * A native messaging manifest's `path` cannot carry arguments, so the artifact
 * must be a self-contained executable that runs the bridge with no subcommand.
 *
 * Pipeline:
 *   1. esbuild  — bundle src/cmd/bridge.ts (+ ws, yaml) into one CJS file.
 *   2. node --experimental-sea-config — turn that bundle into a SEA blob.
 *   3. copy the running node.exe → dist/cmd/bridge.exe.
 *   4. postject — inject the blob into the exe (Windows fuse + no signature).
 *
 * Output: dist/cmd/bridge.exe
 *   This path is the cross-module contract: mateclaw-desktop's
 *   scripts/prepare-resources.mjs copies exactly this file into the desktop
 *   package's <resources>/bridge/bridge.exe.
 *
 * Notes:
 *   - SEA is a stable-enough Node 20+/22 feature; it still prints an
 *     experimental warning, which is harmless.
 *   - On Windows the produced exe is unsigned. postject removes any Authenticode
 *     signature from the copied node.exe so the blob can be appended; re-sign
 *     downstream if distribution requires it.
 */
import { build } from 'esbuild'
import { execFileSync } from 'node:child_process'
import { mkdirSync, copyFileSync, rmSync, existsSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')

const distDir = join(root, 'dist')
const outDir = join(distDir, 'cmd')
const bundlePath = join(distDir, 'bridge.bundle.cjs')
const seaConfigPath = join(distDir, 'sea-config.json')
const blobPath = join(distDir, 'bridge.blob')
const exePath = join(outDir, 'bridge.exe')

function log(msg) {
  process.stdout.write(`[build:exe] ${msg}\n`)
}

async function bundle() {
  mkdirSync(distDir, { recursive: true })
  const result = await build({
    entryPoints: [join(root, 'src', 'cmd', 'bridge.ts')],
    bundle: true,
    platform: 'node',
    format: 'cjs',
    target: 'node22',
    outfile: bundlePath,
    // ws ships an optional native accelerator (bufferutil / utf-8-validate) that
    // it require()s lazily inside try/catch — exclude so esbuild doesn't choke
    // on the missing optional deps. ws falls back to its pure-JS path.
    external: ['bufferutil', 'utf-8-validate'],
    legalComments: 'none',
    logLevel: 'info',
  })
  if (result.errors.length) {
    throw new Error(`esbuild reported ${result.errors.length} error(s)`)
  }
  log(`bundled → ${bundlePath}`)
}

function makeSeaBlob() {
  const seaConfig = {
    main: bundlePath,
    output: blobPath,
    disableExperimentalSEAWarning: true,
  }
  writeFileSync(seaConfigPath, JSON.stringify(seaConfig, null, 2), 'utf8')
  execFileSync(process.execPath, ['--experimental-sea-config', seaConfigPath], {
    stdio: 'inherit',
  })
  if (!existsSync(blobPath)) {
    throw new Error(`SEA blob was not produced at ${blobPath}`)
  }
  log(`sea blob → ${blobPath}`)
}

function injectExe() {
  mkdirSync(outDir, { recursive: true })
  // Start from a copy of the Node runtime that is building this exe.
  copyFileSync(process.execPath, exePath)
  log(`copied node runtime → ${exePath}`)

  // postject is a JS CLI; resolve its bin so this works regardless of PATH.
  const postjectBin = require.resolve('postject/dist/cli.js')
  execFileSync(
    process.execPath,
    [
      postjectBin,
      exePath,
      'NODE_SEA_BLOB',
      blobPath,
      '--sentinel-fuse',
      'NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2',
    ],
    { stdio: 'inherit' },
  )
  log(`injected SEA blob → ${exePath}`)
}

async function main() {
  const bundleOnly = process.argv.includes('--bundle-only')

  await bundle()
  if (bundleOnly) {
    log(`done (bundle only): ${bundlePath}`)
    return
  }

  if (process.platform !== 'win32') {
    log(
      `warning: building on ${process.platform}; the produced exe targets the ` +
        'host platform, not Windows x64. Run this on Windows x64 for bridge.exe.',
    )
  }
  makeSeaBlob()
  injectExe()
  // Clean intermediates; keep the bundle for debugging.
  rmSync(seaConfigPath, { force: true })
  rmSync(blobPath, { force: true })
  log(`done: ${exePath}`)
}

main().catch((err) => {
  process.stderr.write(`[build:exe] failed — ${String(err?.stack ?? err)}\n`)
  process.exit(1)
})
