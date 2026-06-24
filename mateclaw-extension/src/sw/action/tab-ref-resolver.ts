import type { TabRef } from './types'
import type { TabGroupManager } from '../tab-group-manager'

export interface TabRefResolverDeps {
  tabGroupManager: TabGroupManager
  /** Defaults to globalThis.chrome — injectable for tests. */
  chrome?: typeof globalThis.chrome
  /** Identifies the current user — used by getMainTabId. Phase 2 hardcoded; Phase 4 from auth. */
  subject: string
}

/**
 * Resolves a TabRef wire-form value to a concrete Chrome tab id.
 *
 *   "main"   → TabGroupManager.getMainTabId(subject)
 *   "active" → active tab inside the subject's managed MateClaw tab group
 *   <int>    → that integer literally
 *
 * Returns null if resolution fails (no main tab, no managed active tab, or wrong type).
 * Callers should map null to ActionResult.Failure(code='NO_TARGET_TAB').
 */
export class TabRefResolver {
  constructor(private readonly deps: TabRefResolverDeps) {}

  async resolve(
    tabRef: TabRef,
    opts: { createIfMissing?: boolean } = {},
  ): Promise<number | null> {
    if (typeof tabRef === 'number') {
      return tabRef
    }

    if (tabRef === 'main') {
      const bound = await this.deps.tabGroupManager.getMainTabId(this.deps.subject)
      if (bound !== null) {
        // observe/click/type(非 navigate)必须复用 navigate 已建好的同一 tab,不验证窗口。
        if (!opts.createIfMissing) return bound
        // navigate:主 tab 已在独立窗口才复用;否则(旧绑定落在用户普通窗口)往下重建到独立窗口,
        // setMainTabId 会覆盖旧绑定 —— 用户无需手动关旧标签。
        if (await this.deps.tabGroupManager.isMainTabInDedicatedWindow(this.deps.subject)) {
          return bound
        }
      }
      // Only navigate provisions a tab; observe/click/type must reuse the
      // existing one. Scripting a fresh about:blank yields an empty a11y tree
      // (the intermittent "tree is empty" the user hit when the agent's tab had
      // been closed). Missing main + !createIfMissing → null → NO_TARGET_TAB so
      // the orchestrator re-navigates instead of reading a blank page.
      if (!opts.createIfMissing) return null
      // No main tab bound yet. Provision a dedicated agent tab rather than
      // failing (NO_TARGET_TAB) or hijacking whatever the user is looking at.
      // This is what makes "open a page in my browser" work on first use: the
      // agent gets its own visible tab, and the binding persists so follow-up
      // observe/click/type actions resolve "main" to the same tab.
      const chrome = this.deps.chrome ?? globalThis.chrome
      let created: chrome.tabs.Tab
      try {
        // 独立窗口:把 agent/获客的主 tab 开在一个单独的浏览器窗口,与用户当前窗口区分开 ——
        // 后续作者主页 tab 用 openerTabId 自动跟进同一窗口、激活兜底也只 focus 这个窗口,不再
        // 打扰用户正在用的窗口。focused:false 后台创建、不抢焦点;windows API 不可用时回退到
        // 在当前窗口建 tab(老行为),保证可用性不回归。
        if (chrome.windows?.create) {
          // 注意:create 时 state:'maximized' 不能与 focused:false 同传 —— Chrome 会抛
          // "The 'state' property cannot be combined with ..." 被下面的 catch 吞掉 → 返回 null
          // → 窗口根本不打开(踩过的坑)。故先后台创建(focused:false 不抢焦点),再 update 成最大化。
          const win = await chrome.windows.create({ url: 'about:blank', focused: false })
          const winTab = win?.tabs?.[0]
          if (!winTab || typeof winTab.id !== 'number') return null
          // best-effort 最大化:失败(API 不可用等)也不影响窗口已创建,不抛错不返回 null。
          try { if (typeof win.id === 'number') await chrome.windows.update(win.id, { state: 'maximized' }) } catch { /* ignore */ }
          created = winTab
          // 构建标记 + 运行时确认:在 SW 控制台看到这行 => 跑的是含"独立窗口最大化"修复的最新 dist。
          console.log('[mateclaw] 获客独立窗口已创建 win=', win.id, 'tab=', winTab.id, '(build: dedicated-window-maximized)')
        } else {
          created = await chrome.tabs.create({ url: 'about:blank', active: true })
        }
      } catch {
        return null
      }
      if (typeof created.id !== 'number') return null
      await this.deps.tabGroupManager.setMainTabId(this.deps.subject, created.id)
      // Drop the new agent tab into a labeled, colored Chrome tab group so the
      // user can see at a glance which tabs the agent owns (mirrors the
      // official "Claude in Chrome" group). Purely visual — the resolved tab
      // id is unchanged. Best-effort: joinChromeGroup swallows its own errors
      // and the manager returns null when the API is unavailable.
      await this.deps.tabGroupManager.joinChromeGroup(this.deps.subject, created.id)
      return created.id
    }

    if (tabRef === 'active') {
      try {
        const resolver = this.deps.tabGroupManager.getActiveTabId
        if (typeof resolver !== 'function') return null
        return await resolver.call(this.deps.tabGroupManager, this.deps.subject)
      } catch {
        return null
      }
    }

    // Future-proof: an unrecognized string falls through here. Treat as a
    // resolution miss so the router can surface NO_TARGET_TAB.
    return null
  }
}
