import { describe, expect, it, vi } from 'vitest'
import { DebuggerManager } from './debugger-manager'

function fakeChrome() {
  const detachListeners: Array<(source: chrome.debugger.Debuggee, reason: string) => void> = []
  const eventListeners: Array<(source: chrome.debugger.Debuggee, method: string, params?: unknown) => void> = []
  const calls: Array<{ method: string; tabId: number; cdpMethod?: string; params?: unknown }> = []
  let sendResult: unknown = {}
  let holdNextSend = false

  const chromeMock = {
    debugger: {
      attach: vi.fn((target: chrome.debugger.Debuggee, _version: string, cb?: () => void) => {
        calls.push({ method: 'attach', tabId: target.tabId! })
        cb?.()
      }),
      detach: vi.fn((target: chrome.debugger.Debuggee, cb?: () => void) => {
        calls.push({ method: 'detach', tabId: target.tabId! })
        cb?.()
      }),
      sendCommand: vi.fn(
        (
          target: chrome.debugger.Debuggee,
          cdpMethod: string,
          params?: unknown,
          cb?: (result?: unknown) => void,
        ) => {
          calls.push({ method: 'send', tabId: target.tabId!, cdpMethod, params })
          if (!holdNextSend) cb?.(sendResult)
        },
      ),
      onDetach: { addListener: (fn: (source: chrome.debugger.Debuggee, reason: string) => void) => detachListeners.push(fn) },
      onEvent: { addListener: (fn: (source: chrome.debugger.Debuggee, method: string, params?: unknown) => void) => eventListeners.push(fn) },
    },
    runtime: { lastError: undefined as chrome.runtime.LastError | undefined },
  } as unknown as typeof globalThis.chrome

  return {
    chrome: chromeMock,
    triggerDetach: (tabId: number, reason: string) => {
      detachListeners.forEach(fn => fn({ tabId } as chrome.debugger.Debuggee, reason))
    },
    triggerEvent: (tabId: number, method: string, params?: unknown) => {
      eventListeners.forEach(fn => fn({ tabId } as chrome.debugger.Debuggee, method, params))
    },
    calls,
    setSendResult: (result: unknown) => {
      sendResult = result
    },
    holdNextSend: () => {
      holdNextSend = true
    },
    setLastError: (message: string | undefined) => {
      ;(chromeMock.runtime as { lastError?: chrome.runtime.LastError }).lastError =
        message == null ? undefined : { message }
    },
  }
}

describe('DebuggerManager', () => {
  it('attach then detach calls chrome.debugger.attach + detach', async () => {
    const env = fakeChrome()
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)
    await manager.detach(101)

    expect(env.calls).toEqual([
      { method: 'attach', tabId: 101 },
      { method: 'detach', tabId: 101 },
    ])
  })

  it('attach is idempotent - second call does not invoke chrome.debugger.attach again', async () => {
    const env = fakeChrome()
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)
    await manager.attach(101)

    expect(env.calls).toEqual([{ method: 'attach', tabId: 101 }])
  })

  it('send forwards to chrome.debugger.sendCommand with correct method + params', async () => {
    const env = fakeChrome()
    env.setSendResult({ frameId: 'frame-1', loaderId: 'loader-1' })
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)
    const result = await manager.send(101, 'Page.navigate', { url: 'https://example.test' })

    expect(result).toEqual({ frameId: 'frame-1', loaderId: 'loader-1' })
    expect(env.calls).toContainEqual({
      method: 'send',
      tabId: 101,
      cdpMethod: 'Page.navigate',
      params: { url: 'https://example.test' },
    })
  })

  it('send on detached tab throws SessionDetachedError(reason=target_closed)', async () => {
    const env = fakeChrome()
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)
    env.triggerDetach(101, 'target_closed')

    await expect(manager.send(101, 'Runtime.evaluate', { expression: '1 + 1' })).rejects.toMatchObject({
      name: 'SessionDetachedError',
      tabId: 101,
      reason: 'target_closed',
    })
  })

  it('devtools open mid-send rejects pending sends with reason=canceled_by_user', async () => {
    const env = fakeChrome()
    env.holdNextSend()
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)
    const pending = manager.send(101, 'Runtime.evaluate', { expression: 'new Promise(() => {})', awaitPromise: true })
    env.triggerDetach(101, 'canceled_by_user')

    await expect(pending).rejects.toMatchObject({
      name: 'SessionDetachedError',
      tabId: 101,
      reason: 'canceled_by_user',
    })
  })

  it('isAttached returns false after onDetach fires', async () => {
    const env = fakeChrome()
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)
    expect(manager.isAttached(101)).toBe(true)

    env.triggerDetach(101, 'target_closed')

    expect(manager.isAttached(101)).toBe(false)
  })

  it('forwards CDP events to the matching tab listener until unsubscribed', () => {
    const env = fakeChrome()
    const manager = new DebuggerManager(env.chrome)
    const listener = vi.fn()

    const unsubscribe = manager.addEventListener(101, listener)
    env.triggerEvent(202, 'Network.loadingFinished', { requestId: 'other' })
    env.triggerEvent(101, 'Network.loadingFinished', { requestId: 'r1' })
    unsubscribe()
    env.triggerEvent(101, 'Network.loadingFinished', { requestId: 'r2' })

    expect(listener).toHaveBeenCalledExactlyOnceWith({
      tabId: 101,
      sessionId: undefined,
      method: 'Network.loadingFinished',
      params: { requestId: 'r1' },
    })
  })

  it('replaced_with_devtools normalises to canceled_by_user', async () => {
    const env = fakeChrome()
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)
    env.triggerDetach(101, 'replaced_with_devtools')

    await expect(manager.send(101, 'Runtime.evaluate', { expression: '1' })).rejects.toMatchObject({
      name: 'SessionDetachedError',
      tabId: 101,
      reason: 'canceled_by_user',
    })
  })

  it('chrome.runtime.lastError on attach throws meaningful error', async () => {
    const env = fakeChrome()
    env.chrome.debugger.attach = vi.fn((target: chrome.debugger.Debuggee, _version: string, cb?: () => void) => {
      env.calls.push({ method: 'attach', tabId: target.tabId! })
      env.setLastError('Another debugger is already attached to the tab')
      cb?.()
      env.setLastError(undefined)
    }) as unknown as typeof env.chrome.debugger.attach
    const manager = new DebuggerManager(env.chrome)

    await expect(manager.attach(101)).rejects.toMatchObject({
      name: 'SessionDetachedError',
      tabId: 101,
      reason: 'canceled_by_user',
      message: expect.stringContaining('Another debugger is already attached to the tab'),
    })
  })

  it('stale extension debugger attach is cleaned with raw detach and retried once', async () => {
    const env = fakeChrome()
    let attachCalls = 0
    env.chrome.debugger.attach = vi.fn((target: chrome.debugger.Debuggee, _version: string, cb?: () => void) => {
      attachCalls += 1
      env.calls.push({ method: 'attach', tabId: target.tabId! })
      if (attachCalls === 1) {
        env.setLastError('Another debugger is already attached to the tab with id: 101.')
      }
      cb?.()
      env.setLastError(undefined)
    }) as unknown as typeof env.chrome.debugger.attach
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)

    expect(manager.isAttached(101)).toBe(true)
    expect(env.calls).toEqual([
      { method: 'attach', tabId: 101 },
      { method: 'detach', tabId: 101 },
      { method: 'attach', tabId: 101 },
    ])
  })

  it('send lastError clears attached state so a retry can re-attach', async () => {
    const env = fakeChrome()
    env.chrome.debugger.sendCommand = vi.fn(
      (
        target: chrome.debugger.Debuggee,
        cdpMethod: string,
        params?: unknown,
        cb?: (result?: unknown) => void,
      ) => {
        env.calls.push({ method: 'send', tabId: target.tabId!, cdpMethod, params })
        env.setLastError('Detached while handling command.')
        cb?.({})
        env.setLastError(undefined)
      },
    ) as unknown as typeof env.chrome.debugger.sendCommand
    const manager = new DebuggerManager(env.chrome)

    await manager.attach(101)
    await expect(manager.send(101, 'Input.dispatchMouseEvent', { type: 'mouseMoved', x: 1, y: 1 }))
      .rejects.toMatchObject({
        name: 'SessionDetachedError',
        tabId: 101,
        reason: 'unknown',
      })

    expect(manager.isAttached(101)).toBe(false)

    await manager.attach(101)

    expect(env.calls.filter(call => call.method === 'attach')).toHaveLength(2)
  })
})
