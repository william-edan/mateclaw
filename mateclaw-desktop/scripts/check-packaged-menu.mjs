import assert from 'node:assert/strict'
import fs from 'node:fs'

const source = fs.readFileSync(new URL('../src/main.ts', import.meta.url), 'utf8')

assert.match(
  source,
  /autoHideMenuBar:\s*true/,
  'main window should hide the native menu bar',
)

assert.match(
  source,
  /if\s*\(app\.isPackaged\s*&&\s*process\.platform\s*!==\s*'darwin'\)\s*\{[\s\S]*?Menu\.setApplicationMenu\(null\)[\s\S]*?\}/,
  'packaged Windows/Linux app should not install the Electron application menu',
)

assert.match(
  source,
  /if\s*\(app\.isPackaged\s*&&\s*process\.platform\s*!==\s*'darwin'\)\s*\{[\s\S]*?win\.setMenu\(null\)[\s\S]*?\}/,
  'packaged Windows/Linux main window should remove the native window menu',
)

assert.match(
  source,
  /if\s*\(!app\.isPackaged\)\s*\{[\s\S]*?installMenu\(port\)[\s\S]*?\}/,
  'development builds should keep the Electron debug menu',
)
