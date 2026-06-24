/**
 * 扩展连接模式(server-direct 设计)。
 *   - auto  (默认):未配对启动时探测本地常驻 bridge —— 在则 client(桌面),不在则 web(网页,空闲等 pair)。
 *                   桌面/网页用户都无需手动切。
 *   - web   :强制网页直连;未配对时空闲,等网页「点连接」推送 pair。
 *   - client:强制走桌面常驻 bridge / native(现状路径不变)。
 *
 * 纯函数 + 注入 storage,便于单测;不在模块加载期触碰全局 chrome。
 */
export type ConnectionMode = 'auto' | 'web' | 'client'

export const CONNECTION_MODE_KEY = 'connectionMode'

const DEFAULT_MODE: ConnectionMode = 'auto'

/** chrome.storage.local 的最小子集(MV3 下 get/set 返回 Promise)。 */
export interface StorageLike {
  get(keys: string | string[]): Promise<{ [key: string]: any }>
  set(items: Record<string, unknown>): Promise<void>
}

/** 读取连接模式;未设置/非法值/读失败一律回退默认 auto。 */
export async function getConnectionMode(storage: StorageLike): Promise<ConnectionMode> {
  try {
    const got = await storage.get(CONNECTION_MODE_KEY)
    const v = got[CONNECTION_MODE_KEY]
    return v === 'web' || v === 'client' ? v : DEFAULT_MODE
  } catch {
    return DEFAULT_MODE
  }
}

/** 写入连接模式。 */
export async function setConnectionMode(storage: StorageLike, mode: ConnectionMode): Promise<void> {
  await storage.set({ [CONNECTION_MODE_KEY]: mode })
}
