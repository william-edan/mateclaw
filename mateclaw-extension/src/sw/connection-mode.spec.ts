import { describe, it, expect } from 'vitest'
import { getConnectionMode, setConnectionMode, CONNECTION_MODE_KEY } from './connection-mode'

function fakeStorage(initial: Record<string, unknown> = {}) {
  const store: Record<string, unknown> = { ...initial }
  return {
    store,
    get: async (k: string | string[]) => {
      const keys = Array.isArray(k) ? k : [k]
      const out: Record<string, unknown> = {}
      for (const key of keys) if (key in store) out[key] = store[key]
      return out
    },
    set: async (items: Record<string, unknown>) => {
      Object.assign(store, items)
    },
  }
}

describe('connection-mode', () => {
  it('未设置时默认 auto', async () => {
    expect(await getConnectionMode(fakeStorage())).toBe('auto')
  })
  it('存了 web/client 原样返回', async () => {
    expect(await getConnectionMode(fakeStorage({ [CONNECTION_MODE_KEY]: 'web' }))).toBe('web')
    expect(await getConnectionMode(fakeStorage({ [CONNECTION_MODE_KEY]: 'client' }))).toBe('client')
  })
  it('非法值回退 auto', async () => {
    expect(await getConnectionMode(fakeStorage({ [CONNECTION_MODE_KEY]: 'bogus' }))).toBe('auto')
  })
  it('读 storage 抛错也回退 auto', async () => {
    const throwing = {
      get: async () => {
        throw new Error('boom')
      },
      set: async () => {},
    }
    expect(await getConnectionMode(throwing)).toBe('auto')
  })
  it('setConnectionMode 落库', async () => {
    const s = fakeStorage()
    await setConnectionMode(s, 'client')
    expect(s.store[CONNECTION_MODE_KEY]).toBe('client')
  })
})
