import { describe, expect, it } from 'vitest'
import membersView from '../index.vue?raw'

describe('member limit business QR dialog', () => {
  it('uses the shared WeChat business QR asset', () => {
    expect(membersView).toContain("import wechatBusinessQr from '@/assets/qrcode/wechat.png'")
    expect(membersView).toContain(':src="wechatBusinessQr"')
    expect(membersView).not.toContain('/business-qr.svg')
  })
})
