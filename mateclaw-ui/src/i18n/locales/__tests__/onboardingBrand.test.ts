// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import enUS from '../en-US'
import zhCN from '../zh-CN'

describe('onboarding brand copy', () => {
  it('uses the Huafan AI brand in source locale dictionaries', () => {
    expect(zhCN.onboarding.title).toBe('欢迎使用 化帆AI')
    expect(zhCN.onboarding.startUsing).toBe('开始使用 化帆AI')
    expect(enUS.onboarding.title).toBe('Welcome to Huafan AI')
    expect(enUS.onboarding.startUsing).toBe('Start Using Huafan AI')
  })

  it('does not ship stale onboarding copy in bundled static assets', () => {
    const assetsDir = resolve(import.meta.dirname, '../../../../../mateclaw-server/src/main/resources/static/assets')
    const bundledLocaleCopy = readdirSync(assetsDir)
      .filter((file) => /^(?:zh-CN|en-US)-.+\.js$/.test(file))
      .map((file) => readFileSync(resolve(assetsDir, file), 'utf8'))
      .join('\n')

    expect(bundledLocaleCopy).not.toContain('欢迎使用 MateClaw')
    expect(bundledLocaleCopy).not.toContain('开始使用 MateClaw')
    expect(bundledLocaleCopy).not.toContain('Welcome to MateClaw')
    expect(bundledLocaleCopy).not.toContain('Start Using MateClaw')
  })
})
