import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '../useWorkspaceStore'

describe('useWorkspaceStore.reset', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('wipes workspace identity and capabilities so a re-login on the same instance starts clean', () => {
    const store = useWorkspaceStore()
    store.workspaces = [{ id: '5', name: 'B', slug: 'b' }] as never
    store.currentWorkspaceId = '5'
    store.currentCapabilities = new Set(['manage:settings']) as never
    store.accessLoaded = true

    store.reset()

    expect(store.workspaces).toEqual([])
    expect(store.currentWorkspaceId).toBeNull()
    expect(store.accessLoaded).toBe(false)
    expect(store.can('manage:settings' as never)).toBe(false)
  })
})
