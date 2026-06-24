import { describe, expect, it, vi } from 'vitest'
import type { EdgeMessage } from '../shared/edge-protocol'
import { EdgeMessageKind } from '../shared/edge-protocol'
import { TabGroupManager } from './tab-group-manager'

type TabGroup = {
  mainTabId: number | null
  allTabIds: number[]
  chromeGroupId: number | null
  staticIndicatorDismissed?: boolean
}
type StorageShape = { tabGroups: Record<string, TabGroup> }

function fakeChrome(
  storage: StorageShape = { tabGroups: {} },
  opts: { missingTabs?: number[]; nextGroupId?: number } = {},
) {
  const onRemovedListeners: Array<(tabId: number, info: any) => void> = []
  const onCreatedListeners: Array<(tab: chrome.tabs.Tab) => void> = []
  const onPageLoadedListeners: Array<(details: any) => void> = []
  const sentUp: EdgeMessage[] = []
  // Records of the tab-group API calls so tests can assert the official
  // create-then-title/color sequence.
  const groupCalls: Array<{ tabIds: number[]; groupId?: number }> = []
  const updateCalls: Array<{ groupId: number; props: chrome.tabGroups.UpdateProperties }> = []
  const tabMessages: Array<{ tabId: number; message: unknown }> = []
  const tabUpdates: Array<{ tabId: number; props: chrome.tabs.UpdateProperties }> = []
  const windowUpdates: Array<{ windowId: number; props: chrome.windows.UpdateInfo }> = []
  let nextGroupId = opts.nextGroupId ?? 7000
  const missingTabs = new Set(opts.missingTabs ?? [])

  return {
    chrome: {
      storage: {
        local: {
          get: vi.fn(async (key: string) => ({
            [key]: storage[key as keyof StorageShape] ?? {},
          })),
          set: vi.fn(async (patch: Partial<StorageShape>) => Object.assign(storage, patch)),
        },
      },
      tabs: {
        onRemoved: {
          addListener: (fn: any) => onRemovedListeners.push(fn),
        },
        onCreated: {
          addListener: (fn: any) => onCreatedListeners.push(fn),
        },
        // Promise overload: returns a fresh group id when none supplied,
        // echoes the joined group id when one is passed.
        group: vi.fn(async (info: { tabIds: number[]; groupId?: number }) => {
          groupCalls.push({ tabIds: info.tabIds, groupId: info.groupId })
          if (typeof info.groupId === 'number') return info.groupId
          return nextGroupId++
        }),
        sendMessage: vi.fn(async (tabId: number, message: unknown) => {
          tabMessages.push({ tabId, message })
          return { ok: true }
        }),
        update: vi.fn(async (tabId: number, props: chrome.tabs.UpdateProperties) => {
          tabUpdates.push({ tabId, props })
          return { id: tabId, windowId: 901 } as chrome.tabs.Tab
        }),
        get: vi.fn(async (tabId: number) => {
          if (missingTabs.has(tabId)) throw new Error(`No tab with id: ${tabId}`)
          return { id: tabId, windowId: 901 } as chrome.tabs.Tab
        }),
        query: vi.fn(async () => []),
      },
      tabGroups: {
        update: vi.fn(async (groupId: number, props: chrome.tabGroups.UpdateProperties) => {
          updateCalls.push({ groupId, props })
          return { id: groupId, ...props } as unknown as chrome.tabGroups.TabGroup
        }),
      },
      webNavigation: {
        onCompleted: {
          addListener: (fn: any) => onPageLoadedListeners.push(fn),
        },
      },
      windows: {
        update: vi.fn(async (windowId: number, props: chrome.windows.UpdateInfo) => {
          windowUpdates.push({ windowId, props })
          return { id: windowId, focused: props.focused } as chrome.windows.Window
        }),
      },
    } as unknown as typeof globalThis.chrome,
    storage,
    sentUp,
    groupCalls,
    updateCalls,
    tabMessages,
    tabUpdates,
    windowUpdates,
    sendUp: (msg: EdgeMessage) => {
      sentUp.push(msg)
    },
    triggerTabClose: async (tabId: number) => {
      await Promise.all(onRemovedListeners.map(fn => fn(tabId, {})))
    },
    triggerTabCreated: async (tab: chrome.tabs.Tab) => {
      await Promise.all(onCreatedListeners.map(fn => fn(tab)))
    },
    triggerPageLoad: async (tabId: number, url: string, frameId = 0) => {
      await Promise.all(onPageLoadedListeners.map(fn => fn({ tabId, url, frameId })))
    },
  }
}

describe('TabGroupManager', () => {
  it('setMainTabId persists and getMainTabId returns it', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)

    expect(f.storage.tabGroups.alice).toEqual({
      mainTabId: 42,
      allTabIds: [42],
      chromeGroupId: null,
      staticIndicatorDismissed: false,
    })
    expect(await manager.getMainTabId('alice')).toBe(42)
  })

  it('getMainTabId returns null for unknown subject', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    expect(await manager.getMainTabId('alice')).toBeNull()
  })

  it('getMainTabId returns null if the bound tab was later closed', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerTabClose(42)

    expect(await manager.getMainTabId('alice')).toBeNull()
  })

  it('getMainTabId lazily clears a stale bound tab that Chrome no longer has', async () => {
    const f = fakeChrome({
      tabGroups: {
        alice: {
          mainTabId: 42,
          allTabIds: [42],
          chromeGroupId: 7000,
          staticIndicatorDismissed: false,
        },
      },
    }, { missingTabs: [42] })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    expect(await manager.getMainTabId('alice')).toBeNull()
    expect(f.storage.tabGroups.alice).toEqual({
      mainTabId: null,
      allTabIds: [],
      chromeGroupId: null,
      staticIndicatorDismissed: false,
    })
  })

  it('addTab puts tab in allTabIds but does NOT set mainTabId', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.addTab('alice', 42)

    expect(f.storage.tabGroups.alice).toEqual({
      mainTabId: null,
      allTabIds: [42],
      chromeGroupId: null,
      staticIndicatorDismissed: false,
    })
    expect(await manager.getMainTabId('alice')).toBeNull()
  })

  it('tab close emits event.tab.closed envelope with tab_ref=<int>', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerTabClose(42)

    expect(f.sentUp).toHaveLength(1)
    expect(f.sentUp[0]!.kind).toBe(EdgeMessageKind.EventTabClosed)
    expect(f.sentUp[0]!.payload?.tab_ref).toBe(42)
  })

  it('tab close on unmanaged tab does NOT emit envelope', async () => {
    const f = fakeChrome()
    new TabGroupManager(f.chrome, f.sendUp)

    await f.triggerTabClose(99)

    expect(f.sentUp).toHaveLength(0)
  })

  it('page load on managed tab emits event.page.navigated with url', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerPageLoad(42, 'https://x.com')

    expect(f.sentUp).toHaveLength(1)
    expect(f.sentUp[0]!.kind).toBe(EdgeMessageKind.EventPageNavigated)
    expect(f.sentUp[0]!.payload?.tab_ref).toBe(42)
    expect(f.sentUp[0]!.payload?.url).toBe('https://x.com')
  })

  it('page load on unmanaged tab does NOT emit envelope', async () => {
    const f = fakeChrome()
    new TabGroupManager(f.chrome, f.sendUp)

    await f.triggerPageLoad(42, 'https://x.com')

    expect(f.sentUp).toHaveLength(0)
  })

  it('iframe load (frameId != 0) does NOT emit envelope', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerPageLoad(42, 'https://iframe.x.com', 99)

    expect(f.sentUp).toHaveLength(0)
  })

  it('SW restart simulation: load() rehydrates state from chrome.storage.local', async () => {
    const sharedStorage: StorageShape = { tabGroups: {} }
    const first = fakeChrome(sharedStorage)
    const manager1 = new TabGroupManager(first.chrome, first.sendUp)

    await manager1.setMainTabId('alice', 42)

    const second = fakeChrome(sharedStorage)
    const manager2 = new TabGroupManager(second.chrome, second.sendUp)

    await manager2.load()

    expect(await manager2.getMainTabId('alice')).toBe(42)
  })

  it('envelope session_id is "" (P0-1 invariant from Phase 1)', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await f.triggerPageLoad(42, 'https://x.com')
    await f.triggerTabClose(42)

    expect(f.sentUp).toHaveLength(2)
    expect(f.sentUp.every(msg => msg.session_id === '')).toBe(true)
  })

  it('unbind clears mainTabId and allTabIds for that subject', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.addTab('alice', 43)
    await manager.unbind('alice')

    expect(f.storage.tabGroups.alice).toEqual({
      mainTabId: null,
      allTabIds: [],
      chromeGroupId: null,
      staticIndicatorDismissed: false,
    })
    expect(await manager.getMainTabId('alice')).toBeNull()
  })

  // -----------------------------------------------------------------
  // Chrome tab-group integration (mirrors the official extension)
  // -----------------------------------------------------------------

  it('joinChromeGroup creates a titled+colored group on first use', async () => {
    const f = fakeChrome({ tabGroups: {} }, { nextGroupId: 7001 })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    const gid = await manager.joinChromeGroup('alice', 42)

    expect(gid).toBe(7001)
    // Official sequence: chrome.tabs.group({tabIds}) THEN tabGroups.update(...).
    expect(f.groupCalls).toEqual([{ tabIds: [42], groupId: undefined }])
    expect(f.updateCalls).toEqual([
      { groupId: 7001, props: { title: '获客助手', color: 'orange', collapsed: false } },
    ])
    expect(await manager.getChromeGroupId('alice')).toBe(7001)
  })

  it('joinChromeGroup reuses the tracked group for subsequent tabs', async () => {
    const f = fakeChrome({ tabGroups: {} }, { nextGroupId: 7001 })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.joinChromeGroup('alice', 42)
    const gid2 = await manager.joinChromeGroup('alice', 43)

    expect(gid2).toBe(7001)
    // Second join passes the existing groupId and does NOT re-title/color.
    expect(f.groupCalls).toEqual([
      { tabIds: [42], groupId: undefined },
      { tabIds: [43], groupId: 7001 },
    ])
    expect(f.updateCalls).toHaveLength(1)
  })

  it('joinChromeGroup recreates the group if the tracked one was dissolved', async () => {
    const f = fakeChrome({ tabGroups: {} }, { nextGroupId: 7001 })
    // First join into 7001 succeeds; the second (re-join 7001) rejects as if
    // the user closed the group, forcing a fresh create.
    let call = 0
    ;(f.chrome.tabs.group as any).mockImplementation(async (info: { tabIds: number[]; groupId?: number }) => {
      call += 1
      f.groupCalls.push({ tabIds: info.tabIds, groupId: info.groupId })
      if (call === 2) throw new Error('No group with id 7001')
      if (typeof info.groupId === 'number') return info.groupId
      return 7001 + (call > 2 ? 1 : 0)
    })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.joinChromeGroup('alice', 42)
    const gid = await manager.joinChromeGroup('alice', 50)

    expect(gid).toBe(7002)
    expect(await manager.getChromeGroupId('alice')).toBe(7002)
  })

  it('joinChromeGroup is a no-op (returns null) when the tabGroups API is absent', async () => {
    const f = fakeChrome()
    // Strip the API to simulate older Chrome / restricted contexts.
    delete (f.chrome.tabs as any).group
    delete (f.chrome as any).tabGroups
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    const gid = await manager.joinChromeGroup('alice', 42)

    expect(gid).toBeNull()
    expect(await manager.getChromeGroupId('alice')).toBeNull()
  })

  it('closing the last tab clears the tracked chromeGroupId', async () => {
    const f = fakeChrome({ tabGroups: {} }, { nextGroupId: 7001 })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.joinChromeGroup('alice', 42)
    expect(await manager.getChromeGroupId('alice')).toBe(7001)

    await f.triggerTabClose(42)

    expect(await manager.getChromeGroupId('alice')).toBeNull()
  })

  it('joinChromeGroup sends the static controlled-group indicator to the tab', async () => {
    const f = fakeChrome({ tabGroups: {} }, { nextGroupId: 7001 })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.joinChromeGroup('alice', 42)

    expect(f.tabMessages).toContainEqual({
      tabId: 42,
      message: { type: 'SHOW_STATIC_INDICATOR', dismissed: false },
    })
  })

  it('dismissStaticIndicatorForTab persists dismissal and hides it for every tab in the group', async () => {
    const f = fakeChrome({ tabGroups: {} }, { nextGroupId: 7001 })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.addTab('alice', 43)
    f.tabMessages.length = 0

    await manager.dismissStaticIndicatorForTab(43)

    expect(f.storage.tabGroups.alice?.staticIndicatorDismissed).toBe(true)
    expect(f.tabMessages).toEqual([
      { tabId: 42, message: { type: 'HIDE_STATIC_INDICATOR' } },
      { tabId: 43, message: { type: 'HIDE_STATIC_INDICATOR' } },
    ])
  })

  it('static heartbeat helpers identify managed tabs', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)

    expect(await manager.isManagedTab(42)).toBe(true)
    expect(await manager.isManagedTab(99)).toBe(false)
  })

  it('getActiveTabId returns only an active tab inside the managed group', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.joinChromeGroup('alice', 42)
    await manager.addTab('alice', 43)
    ;(f.chrome.tabs.query as any).mockImplementation(async (query: chrome.tabs.QueryInfo) => {
      if ('groupId' in query) return [{ id: 43, active: true }]
      return [{ id: 99, active: true }]
    })

    expect(await manager.getActiveTabId('alice')).toBe(43)
  })

  it('getActiveTabId refuses the browser-global active tab when it is outside the managed group', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    ;(f.chrome.tabs.query as any).mockResolvedValue([{ id: 99, active: true }])

    expect(await manager.getActiveTabId('alice')).toBeNull()
  })

  it('getActiveTabId adopts an active tab opened from a managed tab', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    ;(f.chrome.tabs.query as any).mockResolvedValue([{ id: 77, active: true, openerTabId: 42 }])

    expect(await manager.getActiveTabId('alice')).toBe(77)
    expect(f.storage.tabGroups.alice?.allTabIds).toContain(77)
    expect(f.groupCalls).toContainEqual({ tabIds: [77], groupId: undefined })
  })

  it('new tabs opened from a managed tab join the same MateClaw group', async () => {
    const f = fakeChrome({ tabGroups: {} }, { nextGroupId: 7001 })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.joinChromeGroup('alice', 42)
    f.groupCalls.length = 0
    f.tabMessages.length = 0

    await f.triggerTabCreated({ id: 77, openerTabId: 42 } as chrome.tabs.Tab)

    expect(f.storage.tabGroups.alice?.allTabIds).toContain(77)
    expect(f.groupCalls).toEqual([{ tabIds: [77], groupId: 7001 }])
    expect(f.tabMessages).toContainEqual({
      tabId: 77,
      message: { type: 'SHOW_STATIC_INDICATOR', dismissed: false },
    })
  })

  it('switchToMainTabForTab activates the subject main tab and focuses its window', async () => {
    const f = fakeChrome()
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.addTab('alice', 43)
    await manager.switchToMainTabForTab(43)

    expect(f.tabUpdates).toEqual([{ tabId: 42, props: { active: true } }])
    expect(f.windowUpdates).toEqual([{ windowId: 901, props: { focused: true } }])
  })

  it('markWorking / markDone update the Chrome group title and can reset immediately', async () => {
    const f = fakeChrome({ tabGroups: {} }, { nextGroupId: 7001 })
    const manager = new TabGroupManager(f.chrome, f.sendUp)

    await manager.setMainTabId('alice', 42)
    await manager.joinChromeGroup('alice', 42)
    f.updateCalls.length = 0

    await manager.markWorking('alice')
    await manager.markDone('alice', { resetAfterMs: 0 })

    expect(f.updateCalls).toEqual([
      { groupId: 7001, props: { title: 'MateClaw - Working' } },
      { groupId: 7001, props: { title: 'MateClaw - Done' } },
      { groupId: 7001, props: { title: '获客助手' } },
    ])
  })
})
