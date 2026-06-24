import type { EdgeMessage } from '../shared/edge-protocol'
import { makeEdgeMessage, EdgeMessageKind } from '../shared/edge-protocol'

const STORAGE_KEY = 'tabGroups'

/**
 * Title + color of the Chrome tab group the agent's tabs sit in. Mirrors the
 * official "Claude in Chrome" extension, which titles its group "Claude" and
 * colors it ORANGE (see assets/mcpPermissions-*.js `createGroup`:
 *   `chrome.tabGroups.update(gid, { title: "Claude", color: ORANGE, collapsed: false })`).
 * MateClaw uses its own brand name; orange reads as "the agent's tabs".
 */
const GROUP_TITLE = '获客助手'
const GROUP_TITLE_WORKING = 'MateClaw - Working'
const GROUP_TITLE_DONE = 'MateClaw - Done'
/**
 * Chrome's fixed tab-group palette. The installed @types/chrome in this tree
 * doesn't export `tabGroups.ColorEnum`, so we declare the literal union locally
 * (the wire value is just one of these strings).
 */
type TabGroupColor =
  | 'grey' | 'blue' | 'red' | 'yellow' | 'green' | 'pink' | 'purple' | 'cyan' | 'orange'
const GROUP_COLOR: TabGroupColor = 'orange'

type TabGroup = {
  mainTabId: number | null
  allTabIds: number[]
  /**
   * The Chrome `tabGroups` id every tab in `allTabIds` is grouped under, or
   * null if the agent's tabs have not been grouped yet. Lets us reuse one
   * labeled group per subject and clean it up — the official extension tracks
   * the equivalent `chromeGroupId` in its group metadata.
   */
  chromeGroupId: number | null
  staticIndicatorDismissed: boolean
}

type TabGroups = Record<string, TabGroup>

/**
 * Maps user subject -> managed Chrome tab ids. Persisted to
 * chrome.storage.local so SW restarts are recoverable.
 *
 * State shape on disk:
 *   { tabGroups: { [subject: string]: {
 *       mainTabId: number | null,
 *       allTabIds: number[],
 *       chromeGroupId: number | null,
 *       staticIndicatorDismissed: boolean
 *   } } }
 *
 * `mainTabId` is the tab the user explicitly bound as "main" for that
 * subject (via a sidepanel UI action -- out of scope for this task; we just
 * provide setMainTabId() and reads).
 * `allTabIds` is every tab the user has bound to this subject (Phase 3 will
 * use it for multi-tab orchestration; D1 just maintains it).
 * `chromeGroupId` is the Chrome tab-group id those tabs visibly sit in. It is
 * managed by joinChromeGroup() and mirrors the official extension's labeled,
 * colored agent group.
 */
export class TabGroupManager {
  #groups: TabGroups | null = null
  #mutationQueue = Promise.resolve()
  #titleResetTimers = new Map<string, ReturnType<typeof setTimeout>>()

  constructor(
    private readonly chrome: typeof globalThis.chrome,
    private readonly sendUp: (msg: EdgeMessage) => void,
  ) {
    this.chrome.tabs.onRemoved.addListener(this.#onTabRemoved)
    this.chrome.tabs.onCreated?.addListener?.(this.#onTabCreated)
    this.chrome.webNavigation.onCompleted.addListener(this.#onPageLoaded)
  }

  /** Rehydrate the in-memory cache from chrome.storage.local. */
  async load(): Promise<void> {
    this.#groups = await this.#readGroups()
  }

  /**
   * Returns the chrome tab id explicitly bound as "main" for this subject,
   * or null if none. Returns null also if the bound tab no longer exists
   * (cleaned up via the onRemoved listener).
   */
  async getMainTabId(subject: string): Promise<number | null> {
    const groups = await this.#loadGroups()
    const tabId = groups[subject]?.mainTabId ?? null
    if (typeof tabId !== 'number') return null
    if (typeof this.chrome.tabs?.get !== 'function') return tabId

    try {
      await this.chrome.tabs.get(tabId)
      return tabId
    } catch {
      // The service worker can miss tabs.onRemoved while suspended, leaving a
      // stale mainTabId in storage. Repair it lazily so the next navigate can
      // provision a fresh visible MateClaw tab instead of failing NO_TARGET_TAB.
      await this.#removeTrackedTab(subject, tabId)
      return null
    }
  }

  /**
   * 主 tab 是否处在一个"只属于本 agent"的独立窗口里——窗口内每个 tab 都在 allTabIds。
   * 供 navigate 复用主 tab 前判断:旧绑定可能落在用户的普通窗口(混着用户自己的 tab),此时应
   * 重建到独立窗口而非复用(用户反馈"获客没新开独立窗口"的根因)。能力缺失/查询失败时返回 true
   * (保守,绝不误开新窗口、不破坏现有可用性)。
   */
  async isMainTabInDedicatedWindow(subject: string): Promise<boolean> {
    const group = (await this.#loadGroups())[subject]
    if (!group || typeof group.mainTabId !== 'number') return false
    const tabsGet = this.chrome.tabs?.get
    const tabsQuery = this.chrome.tabs?.query
    if (typeof tabsGet !== 'function' || typeof tabsQuery !== 'function') return true
    try {
      const mainTab = await tabsGet.call(this.chrome.tabs, group.mainTabId)
      if (typeof mainTab.windowId !== 'number') return true
      const winTabs = await tabsQuery.call(this.chrome.tabs, { windowId: mainTab.windowId })
      const managed = new Set(group.allTabIds)
      return winTabs.every(t => typeof t.id === 'number' && managed.has(t.id))
    } catch {
      return true
    }
  }

  /**
   * Bind tabId as the "main" tab for subject. Also adds it to allTabIds.
   * Persists. Preserves any existing chromeGroupId.
   */
  async setMainTabId(subject: string, tabId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject] ?? emptyGroup()
      groups[subject] = {
        mainTabId: tabId,
        allTabIds: uniqueTabIds([...group.allTabIds, tabId]),
        chromeGroupId: group.chromeGroupId,
        staticIndicatorDismissed: group.staticIndicatorDismissed,
      }
    })
    await this.showStaticIndicatorForTab(subject, tabId)
  }

  /** Add a tab to subject's group without making it main. */
  async addTab(subject: string, tabId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject] ?? emptyGroup()
      groups[subject] = {
        mainTabId: group.mainTabId,
        allTabIds: uniqueTabIds([...group.allTabIds, tabId]),
        chromeGroupId: group.chromeGroupId,
        staticIndicatorDismissed: group.staticIndicatorDismissed,
      }
    })
    await this.showStaticIndicatorForTab(subject, tabId)
  }

  /**
   * Returns the Chrome tab-group id the subject's tabs sit in, or null if no
   * group has been created yet.
   */
  async getChromeGroupId(subject: string): Promise<number | null> {
    const groups = await this.#loadGroups()
    return groups[subject]?.chromeGroupId ?? null
  }

  /**
   * Resolve the active Chrome tab only if it is already owned by this subject.
   * This keeps "active" actions inside the visible MateClaw tab group instead
   * of hijacking the admin/chat tab the user may be looking at.
   */
  async getActiveTabId(subject: string): Promise<number | null> {
    const group = (await this.#loadGroups())[subject]
    if (!group || group.allTabIds.length === 0) return null
    if (typeof this.chrome.tabs?.query !== 'function') return null

    let candidates: chrome.tabs.Tab[] = []
    if (typeof group.chromeGroupId === 'number') {
      try {
        candidates = await this.chrome.tabs.query({
          active: true,
          groupId: group.chromeGroupId,
        })
      } catch {
        candidates = []
      }
    }
    try {
      if (candidates.length === 0) candidates = await this.chrome.tabs.query({ active: true })
    } catch {
      return null
    }

    const tracked = new Set(group.allTabIds)
    const first = candidates.find(tab => typeof tab.id === 'number' && tracked.has(tab.id))
    if (typeof first?.id === 'number') return first.id

    const openedByManagedTab = candidates.find(tab =>
      typeof tab.id === 'number' &&
      typeof tab.openerTabId === 'number' &&
      tracked.has(tab.openerTabId)
    )
    if (typeof openedByManagedTab?.id !== 'number') return null

    await this.addTab(subject, openedByManagedTab.id)
    await this.joinChromeGroup(subject, openedByManagedTab.id)
    return openedByManagedTab.id
  }

  /**
   * Make `tabId` join the subject's labeled Chrome tab group, creating the
   * group (titled "MateClaw", orange, expanded) on first use. Mirrors the
   * official extension's findGroupByTab → reuse-or-create flow:
   *
   *   - If we already track a chromeGroupId for this subject, join it:
   *       chrome.tabs.group({ tabIds: [tabId], groupId })
   *   - Otherwise create a fresh group and title/color it:
   *       const gid = await chrome.tabs.group({ tabIds: [tabId] })
   *       await chrome.tabGroups.update(gid, { title, color, collapsed: false })
   *
   * Grouping is purely visual — it never changes the tab id callers resolve.
   * Best-effort: if the chrome.tabGroups API is unavailable or a tracked group
   * was dissolved by the user, we transparently fall back to creating a new
   * one. Failures are swallowed (the agent must still work without grouping).
   *
   * Returns the resulting Chrome group id, or null if grouping failed.
   */
  async joinChromeGroup(subject: string, tabId: number): Promise<number | null> {
    if (!this.chrome.tabs?.group || !this.chrome.tabGroups?.update) {
      // Older Chrome / test fakes without the API. Grouping is optional.
      return null
    }

    // 独立窗口模式:tab 处在"只属于本 agent"的独立窗口时,绝不加入 Chrome 标签分组。
    // Chrome 标签分组不能跨窗口——把独立窗口里的 tab group() 进一个落在别处(用户主窗口/上次
    // 残留窗口)的分组,会把这个 tab 整个拽到那个窗口,这正是"获客独立窗口弹一下又回到原窗口"的
    // 根因。tabs.group 是全工程唯一能跨窗口移动 tab 的调用,独立窗口里直接跳过它——无论旧分组
    // 绑定是否残留、dist 是否最新,都不会再被拽走。独立窗口本身即隔离与视觉区分,分组标签多余。
    if (await this.#isTabInDedicatedWindow(subject, tabId)) {
      return null
    }

    const existing = await this.getChromeGroupId(subject)

    // Try to join the already-tracked group first — but ONLY if that group lives
    // in the SAME window as the tab. Chrome tab groups can't span windows, so
    // grouping a tab into a group in another window MOVES the tab there — exactly
    // the "获客独立窗口弹一下又回到主窗口" bug: the agent tab is created in a fresh
    // isolated window, but the old group lives in the user's main window, so reuse
    // drags it back. When the tracked group is in a different window, skip reuse and
    // create a fresh group in the tab's own window below.
    if (existing !== null && await this.#groupInSameWindowAsTab(existing, tabId)) {
      try {
        await this.chrome.tabs.group({ tabIds: [tabId], groupId: existing })
        await this.#setChromeGroupId(subject, existing)
        await this.showStaticIndicatorForTab(subject, tabId)
        return existing
      } catch {
        // The tracked group was likely closed/dissolved by the user. Fall
        // through and create a fresh one (mirrors the official's
        // adoptOrphanedGroup / recreate behaviour at a reasonable level).
      }
    }

    try {
      const groupId = await this.chrome.tabs.group({ tabIds: [tabId] })
      await this.chrome.tabGroups.update(groupId, {
        title: GROUP_TITLE,
        color: GROUP_COLOR,
        collapsed: false,
      })
      await this.#setChromeGroupId(subject, groupId)
      await this.showStaticIndicatorForTab(subject, tabId)
      return groupId
    } catch {
      // Grouping is a visual nicety; never let it break tab provisioning.
      return null
    }
  }

  /**
   * 已跟踪的分组是否和 tab 处在同一窗口。Chrome 标签分组不能跨窗口:把 tab group() 进另一个
   * 窗口的分组会把它移过去。独立窗口的 agent tab 必须只在同窗口分组,否则被拽回旧分组(主窗口)
   * —— 这正是"获客独立窗口弹一下又回到主窗口"的根因。tabGroups.get / tabs.get 不可用或查询
   * 失败时返回 false(保守:宁可新建分组,绝不把 tab 拽走)。
   */
  async #groupInSameWindowAsTab(groupId: number, tabId: number): Promise<boolean> {
    const groupsGet = this.chrome.tabGroups?.get
    const tabsGet = this.chrome.tabs?.get
    if (typeof groupsGet !== 'function' || typeof tabsGet !== 'function') return false
    try {
      const tab = await tabsGet.call(this.chrome.tabs, tabId)
      const group = await groupsGet.call(this.chrome.tabGroups, groupId)
      return typeof tab.windowId === 'number' && group?.windowId === tab.windowId
    } catch {
      return false
    }
  }

  /**
   * tabId 是否处在一个"只属于本 agent"的独立窗口(窗口内每个 tab 都在 allTabIds)。
   * 供 joinChromeGroup 判断是否跳过分组——独立窗口里 tabs.group() 会把 tab 跨窗口拽走。
   * 与 isMainTabInDedicatedWindow 的"保守方向"相反:这里能力缺失/查询失败/空窗口时返回 false
   * (回退到正常分组,绝不误伤老的"在当前窗口建 tab"路径);那里返回 true(倾向复用主 tab、不无谓重开窗口)。
   */
  async #isTabInDedicatedWindow(subject: string, tabId: number): Promise<boolean> {
    const group = (await this.#loadGroups())[subject]
    if (!group) return false
    const tabsGet = this.chrome.tabs?.get
    const tabsQuery = this.chrome.tabs?.query
    if (typeof tabsGet !== 'function' || typeof tabsQuery !== 'function') return false
    try {
      const tab = await tabsGet.call(this.chrome.tabs, tabId)
      if (typeof tab.windowId !== 'number') return false
      const winTabs = await tabsQuery.call(this.chrome.tabs, { windowId: tab.windowId })
      if (winTabs.length === 0) return false
      const managed = new Set(group.allTabIds)
      return winTabs.every(t => typeof t.id === 'number' && managed.has(t.id))
    } catch {
      return false
    }
  }

  /** Remove the binding. Idempotent. Drops the tracked chromeGroupId too. */
  async unbind(subject: string): Promise<void> {
    const group = (await this.#loadGroups())[subject]
    if (group) {
      await this.hideStaticIndicatorForTabs(group.allTabIds)
      await this.#setChromeGroupTitle(group.chromeGroupId, GROUP_TITLE)
    }
    await this.#mutateGroups(groups => {
      groups[subject] = emptyGroup()
    })
  }

  async markWorking(subject: string): Promise<void> {
    const group = (await this.#loadGroups())[subject]
    if (!group?.chromeGroupId) return
    this.#clearTitleResetTimer(subject)
    await this.#setChromeGroupTitle(group.chromeGroupId, GROUP_TITLE_WORKING)
  }

  async markDone(subject: string, opts: { resetAfterMs?: number } = {}): Promise<void> {
    const group = (await this.#loadGroups())[subject]
    if (!group?.chromeGroupId) return

    await this.#setChromeGroupTitle(group.chromeGroupId, GROUP_TITLE_DONE)
    const resetAfterMs = opts.resetAfterMs ?? 1_200
    if (resetAfterMs <= 0) {
      await this.#setChromeGroupTitle(group.chromeGroupId, GROUP_TITLE)
      return
    }
    const groupId = group.chromeGroupId
    this.#clearTitleResetTimer(subject)
    const timer = setTimeout(() => {
      this.#titleResetTimers.delete(subject)
      void this.#restoreTitleIfStillGroup(subject, groupId)
    }, resetAfterMs)
    this.#titleResetTimers.set(subject, timer)
  }

  async resetChromeGroupTitle(subject: string): Promise<void> {
    const group = (await this.#loadGroups())[subject]
    if (!group?.chromeGroupId) return
    await this.#setChromeGroupTitle(group.chromeGroupId, GROUP_TITLE)
  }

  async showStaticIndicatorForSubject(subject: string): Promise<void> {
    const group = (await this.#loadGroups())[subject]
    if (!group) return
    await Promise.all(group.allTabIds.map(tabId => this.showStaticIndicatorForTab(subject, tabId)))
  }

  async showStaticIndicatorForTab(subject: string, tabId: number): Promise<void> {
    const group = (await this.#loadGroups())[subject]
    if (!group || !group.allTabIds.includes(tabId)) return
    await this.#sendTabMessage(tabId, {
      type: 'SHOW_STATIC_INDICATOR',
      dismissed: group.staticIndicatorDismissed,
    })
  }

  async hideStaticIndicatorForSubject(subject: string): Promise<void> {
    const group = (await this.#loadGroups())[subject]
    if (!group) return
    await this.hideStaticIndicatorForTabs(group.allTabIds)
  }

  async dismissStaticIndicatorForTab(tabId: number): Promise<void> {
    const subject = await this.findSubjectByTab(tabId)
    if (!subject) return
    await this.#mutateGroups(groups => {
      const group = groups[subject]
      if (!group) return
      groups[subject] = {
        ...group,
        allTabIds: [...group.allTabIds],
        staticIndicatorDismissed: true,
      }
    })
    await this.hideStaticIndicatorForSubject(subject)
  }

  async switchToMainTabForTab(tabId: number): Promise<void> {
    const subject = await this.findSubjectByTab(tabId)
    if (!subject) return
    const group = (await this.#loadGroups())[subject]
    const mainTabId = group?.mainTabId
    if (typeof mainTabId !== 'number') return
    try {
      await this.chrome.tabs.update(mainTabId, { active: true })
      const tab = await this.chrome.tabs.get?.(mainTabId)
      if (typeof tab?.windowId === 'number' && this.chrome.windows?.update) {
        await this.chrome.windows.update(tab.windowId, { focused: true })
      }
    } catch {
      // Visual affordance only; if a user closed the tab, normal tab cleanup
      // will repair state on the next onRemoved event.
    }
  }

  async isManagedTab(tabId: number): Promise<boolean> {
    return (await this.findSubjectByTab(tabId)) !== null
  }

  async findSubjectByTab(tabId: number): Promise<string | null> {
    const groups = await this.#loadGroups()
    for (const [subject, group] of Object.entries(groups)) {
      if (group.allTabIds.includes(tabId)) return subject
    }
    return null
  }

  /** Persist the Chrome group id for subject, creating the row if needed. */
  async #setChromeGroupId(subject: string, chromeGroupId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject] ?? emptyGroup()
      groups[subject] = {
        mainTabId: group.mainTabId,
        allTabIds: group.allTabIds,
        chromeGroupId,
        staticIndicatorDismissed: group.staticIndicatorDismissed,
      }
    })
  }

  async #removeTrackedTab(subject: string, tabId: number): Promise<void> {
    await this.#mutateGroups(groups => {
      const group = groups[subject]
      if (!group) return
      const nextTabIds = group.allTabIds.filter(id => id !== tabId)
      groups[subject] = {
        mainTabId: group.mainTabId === tabId ? null : group.mainTabId,
        allTabIds: nextTabIds,
        chromeGroupId: nextTabIds.length === 0 ? null : group.chromeGroupId,
        staticIndicatorDismissed: group.staticIndicatorDismissed,
      }
    })
  }

  #onTabRemoved = async (tabId: number, _info: chrome.tabs.TabRemoveInfo) => {
    let changedSubjectCount = 0

    await this.#mutateGroups(groups => {
      for (const [subject, group] of Object.entries(groups)) {
        const nextTabIds = group.allTabIds.filter(id => id !== tabId)
        const nextMainTabId = group.mainTabId === tabId ? null : group.mainTabId
        const changed =
          nextMainTabId !== group.mainTabId ||
          nextTabIds.length !== group.allTabIds.length

        if (!changed) continue

        // Chrome auto-removes an empty tab group; drop our stale id so the
        // next joinChromeGroup() creates a fresh labeled group rather than
        // trying to add to a group that no longer exists.
        const nextChromeGroupId = nextTabIds.length === 0 ? null : group.chromeGroupId

        groups[subject] = {
          mainTabId: nextMainTabId,
          allTabIds: nextTabIds,
          chromeGroupId: nextChromeGroupId,
          staticIndicatorDismissed: group.staticIndicatorDismissed,
        }
        changedSubjectCount += 1
      }
    })

    for (let i = 0; i < changedSubjectCount; i += 1) {
      this.sendUp(makeEdgeMessage({
        kind: EdgeMessageKind.EventTabClosed,
        payload: { tab_ref: tabId },
      }))
    }
  }

  #onTabCreated = async (tab: chrome.tabs.Tab) => {
    if (typeof tab.id !== 'number' || typeof tab.openerTabId !== 'number') return
    const subject = await this.findSubjectByTab(tab.openerTabId)
    if (!subject) return

    await this.addTab(subject, tab.id)
    await this.joinChromeGroup(subject, tab.id)
  }

  #onPageLoaded = async (details: chrome.webNavigation.WebNavigationFramedCallbackDetails) => {
    if (details.frameId !== 0) return

    const groups = await this.#loadGroups()
    const subject = subjectForTab(groups, details.tabId)
    if (!subject) return

    this.sendUp(makeEdgeMessage({
      kind: EdgeMessageKind.EventPageNavigated,
      payload: { tab_ref: details.tabId, url: details.url },
    }))
    await this.showStaticIndicatorForTab(subject, details.tabId)
  }

  async hideStaticIndicatorForTabs(tabIds: number[]): Promise<void> {
    await Promise.all(
      uniqueTabIds(tabIds).map(tabId => this.#sendTabMessage(tabId, { type: 'HIDE_STATIC_INDICATOR' })),
    )
  }

  async #loadGroups(): Promise<TabGroups> {
    if (this.#groups) return this.#groups
    this.#groups = await this.#readGroups()
    return this.#groups
  }

  async #readGroups(): Promise<TabGroups> {
    const result = await this.chrome.storage.local.get(STORAGE_KEY) as Record<string, unknown>
    return normalizeGroups(result[STORAGE_KEY])
  }

  async #saveGroups(groups: TabGroups): Promise<void> {
    this.#groups = groups
    await this.chrome.storage.local.set({ [STORAGE_KEY]: groups })
  }

  async #mutateGroups(mutator: (groups: TabGroups) => void): Promise<void> {
    const run = async () => {
      const groups = cloneGroups(await this.#loadGroups())
      mutator(groups)
      await this.#saveGroups(groups)
    }

    const next = this.#mutationQueue.then(run, run)
    this.#mutationQueue = next.catch(() => undefined)
    await next
  }

  async #restoreTitleIfStillGroup(subject: string, groupId: number): Promise<void> {
    const group = (await this.#loadGroups())[subject]
    if (group?.chromeGroupId !== groupId) return
    await this.#setChromeGroupTitle(groupId, GROUP_TITLE)
  }

  #clearTitleResetTimer(subject: string): void {
    const timer = this.#titleResetTimers.get(subject)
    if (timer === undefined) return
    clearTimeout(timer)
    this.#titleResetTimers.delete(subject)
  }

  async #setChromeGroupTitle(groupId: number | null, title: string): Promise<void> {
    if (typeof groupId !== 'number' || !this.chrome.tabGroups?.update) return
    try {
      await this.chrome.tabGroups.update(groupId, { title })
    } catch {
      // Best-effort styling; user may have dissolved the group manually.
    }
  }

  async #sendTabMessage(tabId: number, message: Record<string, unknown>): Promise<void> {
    const sendMessage = this.chrome.tabs?.sendMessage
    if (typeof sendMessage !== 'function') return
    try {
      await sendMessage(tabId, message)
    } catch {
      // Content script may not be present on chrome://, about:, PDF viewer, or
      // during navigation. The next completed navigation rebroadcasts.
    }
  }
}

function emptyGroup(): TabGroup {
  return { mainTabId: null, allTabIds: [], chromeGroupId: null, staticIndicatorDismissed: false }
}

function uniqueTabIds(tabIds: number[]): number[] {
  return [...new Set(tabIds)]
}

function cloneGroups(groups: TabGroups): TabGroups {
  return Object.fromEntries(
    Object.entries(groups).map(([subject, group]) => [
      subject,
      {
        mainTabId: group.mainTabId,
        allTabIds: [...group.allTabIds],
        chromeGroupId: group.chromeGroupId,
        staticIndicatorDismissed: group.staticIndicatorDismissed,
      },
    ]),
  )
}

function normalizeGroups(raw: unknown): TabGroups {
  if (!raw || typeof raw !== 'object') return {}

  const groups: TabGroups = {}
  for (const [subject, value] of Object.entries(raw)) {
    if (!value || typeof value !== 'object') continue

    const maybeGroup = value as Partial<TabGroup>
    const mainTabId = typeof maybeGroup.mainTabId === 'number' ? maybeGroup.mainTabId : null
    const allTabIds = Array.isArray(maybeGroup.allTabIds)
      ? uniqueTabIds(maybeGroup.allTabIds.filter((id): id is number => typeof id === 'number'))
      : []
    // Tolerate state persisted before chromeGroupId existed (defaults null).
    const chromeGroupId = typeof maybeGroup.chromeGroupId === 'number'
      ? maybeGroup.chromeGroupId
      : null
    const staticIndicatorDismissed = maybeGroup.staticIndicatorDismissed === true

    groups[subject] = { mainTabId, allTabIds, chromeGroupId, staticIndicatorDismissed }
  }

  return groups
}

function subjectForTab(groups: TabGroups, tabId: number): string | null {
  for (const [subject, group] of Object.entries(groups)) {
    if (group.allTabIds.includes(tabId)) return subject
  }
  return null
}
