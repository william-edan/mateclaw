import type { CDP, CDPEvents } from './cdp-types'

/**
 * Typed error thrown when a chrome.debugger session is detached
 * unexpectedly mid-action. Distinct reasons are mapped by action handlers to
 * NO_TARGET_TAB / DEVTOOLS_OPEN / SESSION_DETACHED at the wire level.
 */
export class SessionDetachedError extends Error {
  constructor(
    public readonly tabId: number,
    public readonly reason: DetachReason,
    detail?: string,
  ) {
    super(
      detail
        ? `debugger session for tab ${tabId} detached: ${reason}: ${detail}`
        : `debugger session for tab ${tabId} detached: ${reason}`,
    )
    this.name = 'SessionDetachedError'
  }
}

export type DetachReason =
  | 'target_closed'
  | 'canceled_by_user'
  | 'replaced_with_devtools'
  | 'unknown'

export interface DebuggerEvent<M extends keyof CDPEvents = keyof CDPEvents> {
  tabId: number
  sessionId?: string
  method: M
  params: CDPEvents[M]
}

export type DebuggerEventListener = (event: DebuggerEvent) => void

export class DebuggerManager {
  /** Per-tab attached state. Absence = not attached. */
  private readonly sessions = new Map<number, AttachedSession>()
  private readonly detachedReasons = new Map<number, DetachReason>()
  private readonly eventListeners = new Map<number, Set<DebuggerEventListener>>()
  /** 每个 tab 的"空闲延迟 detach"定时器(防抖释放 CDP 会话,见 {@link scheduleIdleDetach})。 */
  private readonly idleDetachTimers = new Map<number, ReturnType<typeof setTimeout>>()

  constructor(private readonly chrome: typeof globalThis.chrome) {
    this.chrome.debugger.onDetach.addListener(this.onDetach)
    this.chrome.debugger.onEvent?.addListener?.(this.onEvent)
  }

  /**
   * Attach to a tab. Idempotent: re-attaching to an already attached tab
   * returns the existing session without issuing another CDP attach call.
   */
  async attach(tabId: number): Promise<void> {
    // 新的一次使用 → 取消任何待执行的空闲 detach,复用同一会话(横幅不闪、页面不跳)。
    this.cancelIdleDetach(tabId)
    if (this.sessions.has(tabId)) return

    try {
      await this.attachOnce(tabId)
    } catch (error) {
      if (!isAlreadyAttachedError(error)) throw error

      // MV3 service workers can be restarted while Chrome still considers the
      // extension's debugger attached to the tab. Our in-memory map is then
      // empty, and the next attach fails with "Another debugger is already
      // attached". Try a raw detach to release that stale binding, then attach
      // exactly once more. If DevTools or another extension owns it, retry still
      // fails and the caller sees the original class of error.
      await this.forceDetach(tabId)
      await this.attachOnce(tabId)
    }
  }

  private async attachOnce(tabId: number): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      this.chrome.debugger.attach({ tabId }, '1.3', () => {
        const lastError = this.chrome.runtime.lastError
        if (lastError) {
          reject(new SessionDetachedError(tabId, reasonFromLastError(lastError.message), lastError.message))
          return
        }

        this.detachedReasons.delete(tabId)
        this.sessions.set(tabId, { tabId, pending: [] })
        resolve()
      })
    })
  }

  /**
   * Detach from a tab. No-op if not attached. Never throws while cleaning up.
   */
  async detach(tabId: number): Promise<void> {
    this.cancelIdleDetach(tabId)
    if (!this.sessions.has(tabId)) return

    await this.detachChrome(tabId)
    this.sessions.delete(tabId)
    this.detachedReasons.delete(tabId)
  }

  /** 取消某 tab 待执行的空闲 detach 定时器(若有)。 */
  private cancelIdleDetach(tabId: number): void {
    const t = this.idleDetachTimers.get(tabId)
    if (t !== undefined) {
      clearTimeout(t)
      this.idleDetachTimers.delete(tabId)
    }
  }

  /**
   * 防抖延迟 detach:截图/快照后【不要】立即 detach —— CDP 调试横幅("已开始调试此浏览器")会随
   * 每次 attach/detach"出现→消失",页面被顶下去再弹回来,连续截图时表现为整页反复上下跳动
   * (用户看到的"页面变形")。改为延迟 {@code delayMs} 再 detach;期间任何新的 attach(下一次截图)
   * 都会 {@link cancelIdleDetach} 取消本次延迟并复用同一会话 → 跑获客连续截图时横幅稳定不闪、页面
   * 不跳;真正空闲 {@code delayMs} 后才释放、横幅消失。tab 关闭时 Chrome 会自动 detach,无泄漏。
   */
  scheduleIdleDetach(tabId: number, delayMs = 30_000): void {
    this.cancelIdleDetach(tabId)
    if (!this.sessions.has(tabId)) return
    const timer = setTimeout(() => {
      this.idleDetachTimers.delete(tabId)
      void this.detach(tabId)
    }, delayMs)
    this.idleDetachTimers.set(tabId, timer)
  }

  private async forceDetach(tabId: number): Promise<void> {
    await this.detachChrome(tabId)
    this.sessions.delete(tabId)
    this.detachedReasons.delete(tabId)
  }

  private async detachChrome(tabId: number): Promise<void> {
    await new Promise<void>(resolve => {
      this.chrome.debugger.detach({ tabId }, () => {
        resolve()
      })
    })
  }

  /**
   * Send a CDP command on the attached session. Throws SessionDetachedError if
   * the session is gone so action handlers can bail out cleanly.
   */
  async send<M extends keyof CDP>(
    tabId: number,
    method: M,
    params: CDP[M]['params'],
    sessionId?: string,
  ): Promise<CDP[M]['result']> {
    const session = this.sessions.get(tabId)
    if (!session) {
      throw new SessionDetachedError(tabId, this.detachedReasons.get(tabId) ?? 'unknown')
    }

    return new Promise<CDP[M]['result']>((resolve, reject) => {
      const pending: PendingSend = {
        resolve: value => resolve(value as CDP[M]['result']),
        reject,
      }
      session.pending.push(pending)

      const target: chrome.debugger.Debuggee = sessionId ? { tabId, sessionId } : { tabId }
      this.chrome.debugger.sendCommand(target, method as string, params, result => {
        removePending(session, pending)

        const lastError = this.chrome.runtime.lastError
        if (lastError) {
          const reason = reasonFromLastError(lastError.message)
          this.detachedReasons.set(tabId, reason)
          this.sessions.delete(tabId)
          session.pending.length = 0
          reject(new SessionDetachedError(tabId, reason, lastError.message))
          return
        }

        resolve(result as CDP[M]['result'])
      })
    })
  }

  /** True if we have a live debugger session for this tab. */
  isAttached(tabId: number): boolean {
    return this.sessions.has(tabId)
  }

  /**
   * Subscribe to CDP events for one tab. The caller owns the returned cleanup
   * function; DebuggerManager only multiplexes Chrome's single onEvent stream.
   */
  addEventListener(tabId: number, listener: DebuggerEventListener): () => void {
    let listeners = this.eventListeners.get(tabId)
    if (!listeners) {
      listeners = new Set()
      this.eventListeners.set(tabId, listeners)
    }
    listeners.add(listener)

    return () => {
      const current = this.eventListeners.get(tabId)
      if (!current) return
      current.delete(listener)
      if (current.size === 0) this.eventListeners.delete(tabId)
    }
  }

  private readonly onEvent = (
    source: chrome.debugger.Debuggee,
    method: string,
    params?: unknown,
  ) => {
    if (source.tabId == null) return
    const listeners = this.eventListeners.get(source.tabId)
    if (!listeners || listeners.size === 0) return

    const event = {
      tabId: source.tabId,
      sessionId: typeof source.sessionId === 'string' ? source.sessionId : undefined,
      method,
      params: params ?? {},
    } as DebuggerEvent
    for (const listener of [...listeners]) {
      try {
        listener(event)
      } catch (err) {
        console.warn('[mateclaw][sw] debugger event listener threw', err)
      }
    }
  }

  private readonly onDetach = (source: chrome.debugger.Debuggee, reason: string) => {
    if (source.tabId == null) return

    const detachReason = normalizeReason(reason)
    this.detachedReasons.set(source.tabId, detachReason)

    const session = this.sessions.get(source.tabId)
    if (!session) return

    session.detachReason = detachReason
    session.pending.forEach(p => p.reject(new SessionDetachedError(source.tabId!, detachReason)))
    session.pending.length = 0
    this.sessions.delete(source.tabId)
  }
}

interface AttachedSession {
  tabId: number
  /** In-flight CDP send() promises so onDetach can reject them. */
  pending: PendingSend[]
  detachReason?: DetachReason
}

interface PendingSend {
  resolve: (v: unknown) => void
  reject: (e: Error) => void
}

function normalizeReason(raw: string): DetachReason {
  if (raw === 'target_closed') return 'target_closed'
  if (raw === 'canceled_by_user' || raw === 'replaced_with_devtools') return 'canceled_by_user'
  return 'unknown'
}

function reasonFromLastError(message: string | undefined): DetachReason {
  const lower = message?.toLowerCase() ?? ''
  if (lower.includes('debugger') || lower.includes('devtools') || lower.includes('user')) return 'canceled_by_user'
  if (lower.includes('target') || lower.includes('tab')) return 'target_closed'
  return 'unknown'
}

function isAlreadyAttachedError(error: unknown): boolean {
  if (!(error instanceof SessionDetachedError)) return false
  return error.message.toLowerCase().includes('another debugger is already attached')
}

function removePending(session: AttachedSession, pending: PendingSend): void {
  const idx = session.pending.indexOf(pending)
  if (idx >= 0) session.pending.splice(idx, 1)
}
