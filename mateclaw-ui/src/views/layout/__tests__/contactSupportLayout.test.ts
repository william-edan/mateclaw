import { describe, expect, it } from 'vitest'
import layout from '../MainLayout.vue?raw'
import zh from '../../../i18n/locales/zh-CN.ts?raw'
import en from '../../../i18n/locales/en-US.ts?raw'

describe('contact support sidebar entry', () => {
  it('exposes a left-nav action that opens the business QR modal', () => {
    expect(layout).toContain("action: 'contactSupport'")
    expect(layout).toContain("t('nav.contactSupport')")
    expect(layout).toContain('showContactSupport')
    expect(layout).toContain("import wechatBusinessQr from '@/assets/qrcode/wechat.png'")
    expect(layout).toContain('<img :src="wechatBusinessQr" :alt="t(\'contactSupport.qrAlt\')" />')
  })

  it('defines localized contact support copy', () => {
    expect(zh).toContain("contactSupport: '联系客服'")
    expect(zh).toContain('扫码联系客服')
    expect(en).toContain("contactSupport: 'Contact Support'")
    expect(en).toContain('Scan the QR code')
  })
})

describe('account expiry placement', () => {
  it('shows account expiry in the logo metadata slot instead of the package version', () => {
    expect(layout).toContain('class="logo-expiry"')
    expect(layout).toContain('{{ accountExpiryText }}')
    expect(layout).not.toContain('logo-version')
    expect(layout).not.toContain('appVersion')
  })
})
