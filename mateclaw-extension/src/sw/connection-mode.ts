/**
 * 扩展连接方式(server-direct 设计)。
 *   - web   (默认):网页直连;登录网页点连接即可,免客户端。未配对时空闲,等网页 pair。
 *   - client:走桌面客户端常驻 bridge / native。
 *
 * 纯函数 + 注入 storage,便于单测;不在模块加载期触碰全局 chrome。
 */
export type ConnectionMode = 'web' | 'client'

export const CONNECTION_MODE_KEY = 'connectionMode'

const DEFAULT_MODE: ConnectionMode = 'web'

/** chrome.storage.local 的最小子集(MV3 下 get/set 返回 Promise)。 */
export interface StorageLike {
  get(keys: string | string[]): Promise<{ [key: string]: any }>
  set(items: Record<string, unknown>): Promise<void>
}

/** 读取连接方式;未设置/非法值/读失败一律回退默认 web(网页端)。 */
export async function getConnectionMode(storage: StorageLike): Promise<ConnectionMode> {
  try {
    const got = await storage.get(CONNECTION_MODE_KEY)
    return got[CONNECTION_MODE_KEY] === 'client' ? 'client' : DEFAULT_MODE
  } catch {
    return DEFAULT_MODE
  }
}

/** 写入连接方式。 */
export async function setConnectionMode(storage: StorageLike, mode: ConnectionMode): Promise<void> {
  await storage.set({ [CONNECTION_MODE_KEY]: mode })
}
