import { describe, expect, it } from 'vitest'
import providerCard from '../ProviderCard.vue?raw'

describe('provider card recharge QR dialog', () => {
  it('uses the WeChat business QR asset for recharge', () => {
    expect(providerCard).toContain("import wechatBusinessQr from '@/assets/qrcode/wechat.png'")
    expect(providerCard).toContain('src="${wechatBusinessQr}"')
    expect(providerCard).not.toContain('/business-qr.svg')
  })
})
