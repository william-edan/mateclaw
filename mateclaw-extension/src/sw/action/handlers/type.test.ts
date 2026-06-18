import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'
import { ActionFailureError } from '../ActionExecutor'
import { typeHandler } from './type'

// 临时调试开关 DOM_ONLY_NO_CDP_FALLBACK 生产默认 true(type 内部走 click 的 DOM 路径、
// DOM 失败不回退 CDP)。本套件覆盖的是"DOM 写入失败 → 回退 CDP click"旧行为,故关掉它。见 debug-flags.ts。
vi.mock('./debug-flags', () => ({ DOM_ONLY_NO_CDP_FALLBACK: false }))

function fakeDebugger() {
  const sent: Array<{ tabId: number; method: string; params: any }> = []
  const debuggerStub = {
    attach: vi.fn(async () => {}),
    detach: vi.fn(async () => {}),
    send: vi.fn(async (tabId: number, method: string, params: any) => {
      sent.push({ tabId, method, params })
      return {}
    }),
    isAttached: () => true,
  } as unknown as DebuggerManager

  return { debuggerStub, sent }
}

function chromeWithDomExecution() {
  return {
    scripting: {
      executeScript: vi.fn(async ({ func, args }) => [{
        result: func(...args),
      }]),
    },
  } as unknown as typeof chrome
}

function keyParams(sent: Array<{ tabId: number; method: string; params: any }>) {
  return sent.filter(entry => entry.method === 'Input.dispatchKeyEvent').map(entry => entry.params)
}

function insertTextParams(sent: Array<{ tabId: number; method: string; params: any }>) {
  return sent.filter(entry => entry.method === 'Input.insertText').map(entry => entry.params)
}

async function flushPromises(times: number): Promise<void> {
  for (let i = 0; i < times; i++) {
    await Promise.resolve()
  }
}

describe('type handler', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('types "abc" through Input.insertText so React inputs receive one stable text insertion', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    const result = await handler(42, { text: 'abc' }, 5000)

    expect(result.ok).toBe(true)
    // Regression guard for the "openclaw"->"ooppeennccllaaww" doubling:
    // printable text is inserted once via CDP Input.insertText, not via a
    // keyDown+char combination that some pages double-handle.
    expect(insertTextParams(sent)).toEqual([{ text: 'abc' }])
    expect(keyParams(sent)).toEqual([])
  })

  it('a TRAILING literal "\\n" (backslash+n, what LLMs send) becomes Enter, not typed chars', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    // The LLM emitted text="openclaw\\n" → JSON-decoded to the 10-char string
    // openclaw + backslash + n. Must type "openclaw" then press ENTER — and must
    // NOT type a literal backslash.
    await handler(42, { text: 'openclaw\\n' }, 5000)

    const keys = keyParams(sent).map(p => [p.type, p.text, p.key])
    // No event should carry a backslash as its text/key.
    expect(keys.every(([, text, key]) => text !== '\\' && key !== '\\')).toBe(true)
    expect(insertTextParams(sent)).toEqual([{ text: 'openclaw' }])
    // Enter remains a real control key so it submits the focused field.
    expect(keys).toEqual([
      ['keyDown', '', 'Enter'],
      ['keyUp', '', 'Enter'],
    ])
  })

  it('clearFirst=true prepends Ctrl+A + Delete so a re-type REPLACES the field (no openclawopenclaw)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, clearFirst: true, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: 'hi' }, 5000)

    // First four events are the clear prologue: Ctrl+A (modifiers=2) then Delete.
    const k = keyParams(sent)
    expect([k[0]!.type, k[0]!.key, k[0]!.modifiers]).toEqual(['keyDown', 'a', 2])
    expect([k[1]!.type, k[1]!.key, k[1]!.modifiers]).toEqual(['keyUp', 'a', 2])
    expect([k[2]!.type, k[2]!.key]).toEqual(['keyDown', 'Delete'])
    expect([k[3]!.type, k[3]!.key]).toEqual(['keyUp', 'Delete'])
    // Then the normal h,i typing is a single insertText operation.
    expect(k.slice(4)).toEqual([])
    expect(insertTextParams(sent)).toEqual([{ text: 'hi' }])
  })

  it('clearFirst defaults off — no clear prologue (existing callers/tests unaffected)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: 'x' }, 5000)

    // No Ctrl+A/Delete — straight to one insertText operation.
    expect(keyParams(sent)).toEqual([])
    expect(insertTextParams(sent)).toEqual([{ text: 'x' }])
  })

  it('Enter (\\n) sends keyDown + keyUp only (no char event — control keys do not insert text)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: '\n' }, 5000)

    expect(keyParams(sent).map(params => [params.type, params.key, params.windowsVirtualKeyCode])).toEqual([
      ['keyDown', 'Enter', 13],
      ['keyUp', 'Enter', 13],
    ])
  })

  it('with focus_target uses DOM write first so skill inputs avoid slow CDP click+settle', async () => {
    document.body.innerHTML = `<input id="q" placeholder="搜索你感兴趣的内容" value="old">`
    const input = document.querySelector('#q') as HTMLInputElement
    vi.spyOn(document, 'elementFromPoint').mockReturnValue(input)
    const events: string[] = []
    input.addEventListener('input', () => events.push('input'))
    input.addEventListener('change', () => events.push('change'))

    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({
      debugger: debuggerStub,
      chrome: chromeWithDomExecution(),
      clearFirst: true,
      keystrokeIntervalMs: () => 0,
    })

    const result = await handler(42, { text: 'openclaw', focus_target: { x: 30, y: 40 } }, 5000)

    expect(result.ok).toBe(true)
    expect(input.value).toBe('openclaw')
    expect(events).toEqual(['input', 'change'])
    expect(debuggerStub.attach).not.toHaveBeenCalled()
    expect(sent).toEqual([])
  })

  it('with focus_target and trailing Enter writes text then dispatches Enter keyboard events in DOM path', async () => {
    document.body.innerHTML = `<input id="q" placeholder="搜索你感兴趣的内容" value="">`
    const input = document.querySelector('#q') as HTMLInputElement
    vi.spyOn(document, 'elementFromPoint').mockReturnValue(input)
    const keys: string[] = []
    input.addEventListener('keydown', event => keys.push(`down:${event.key}`))
    input.addEventListener('keypress', event => keys.push(`press:${event.key}`))
    input.addEventListener('keyup', event => keys.push(`up:${event.key}`))

    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({
      debugger: debuggerStub,
      chrome: chromeWithDomExecution(),
      clearFirst: true,
      keystrokeIntervalMs: () => 0,
    })

    const result = await handler(42, { text: 'openclaw\n', focus_target: { x: 30, y: 40 } }, 5000)

    expect(result.ok).toBe(true)
    expect(input.value).toBe('openclaw')
    expect(keys).toEqual(['down:Enter', 'press:Enter', 'up:Enter'])
    expect(debuggerStub.attach).not.toHaveBeenCalled()
    expect(sent).toEqual([])
  })

  it('falls back to CDP click and insertText when DOM write cannot find an editable target', async () => {
    document.body.innerHTML = `<button id="not-input">Not input</button>`
    ;(document.activeElement as HTMLElement | null)?.blur?.()
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({
      debugger: debuggerStub,
      chrome: chromeWithDomExecution(),
      keystrokeIntervalMs: () => 0,
    })
    vi.spyOn(document, 'elementFromPoint').mockReturnValue(document.querySelector('#not-input'))

    await handler(42, { text: 'a', focus_target: { x: 30, y: 40 } }, 5000)

    expect(sent[0]).toMatchObject({
      method: 'Input.dispatchMouseEvent',
      params: { type: 'mousePressed', x: 30, y: 40, button: 'left', clickCount: 1 },
    })
    expect(sent[1]).toMatchObject({
      method: 'Input.dispatchMouseEvent',
      params: { type: 'mouseReleased', x: 30, y: 40, button: 'left', clickCount: 1 },
    })
    expect(sent[2]).toMatchObject({
      method: 'Input.insertText',
      params: { text: 'a' },
    })
  })

  it('without focus_target sends only insertText for printable text', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: 'ab' }, 5000)

    expect(sent.map(entry => entry.method)).toEqual(['Input.insertText'])
    expect(insertTextParams(sent)).toEqual([{ text: 'ab' }])
  })

  it('returns Success with chars_typed = text.length', async () => {
    const { debuggerStub } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, clock: () => 50_000, keystrokeIntervalMs: () => 0 })

    const result = await handler(42, { text: 'hello' }, 5000)

    expect(result).toEqual({
      ok: true,
      elapsed_ms: 0,
      payload: { chars_typed: 5 },
    })
  })

  it('empty text returns Success with chars_typed=0 and no CDP calls', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    const result = await handler(42, { text: '' }, 5000)

    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.payload).toEqual({ chars_typed: 0 })
    }
    expect(sent).toHaveLength(0)
  })

  it('uses injected clock+random for deterministic keystroke intervals', async () => {
    vi.useFakeTimers()
    const { debuggerStub, sent } = fakeDebugger()
    const clock = vi.fn(() => 20_000)
    const random = vi.fn(() => 0.5)
    const handler = typeHandler({ debugger: debuggerStub, clock, random })

    const pending = handler(42, { text: 'ab' }, 5000)

    await flushPromises(8)
    expect(sent).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(43)
    expect(sent).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises(8)
    expect(sent).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(44)
    const result = await pending

    expect(result.ok).toBe(true)
    expect(sent).toHaveLength(1)
    expect(clock).toHaveBeenCalled()
    expect(random).toHaveBeenCalled()
  })

  it('SESSION_DETACHED during typing throws ActionFailureError', async () => {
    const { debuggerStub } = fakeDebugger()
    vi.mocked(debuggerStub.send).mockRejectedValueOnce(new SessionDetachedError(42, 'target_closed'))
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await expect(handler(42, { text: 'a' }, 5000)).rejects.toMatchObject({
      name: 'ActionFailureError',
      code: 'SESSION_DETACHED',
      retryable: true,
    } satisfies Partial<ActionFailureError>)
  })

  it('handles unicode characters (text includes 你好)', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: '你好' }, 5000)

    expect(insertTextParams(sent)).toEqual([{ text: '你好' }])
  })

  it('handles special keys via Enter / Tab / Backspace literal in text', async () => {
    const { debuggerStub, sent } = fakeDebugger()
    const handler = typeHandler({ debugger: debuggerStub, keystrokeIntervalMs: () => 0 })

    await handler(42, { text: '\n\t\b' }, 5000)

    // Control keys emit keyDown + keyUp ONLY (no char event — they don't insert
    // text; their keyDown drives submit/focus/delete). Text is empty on both
    // (text would belong to a char event, which we don't send for these).
    expect(keyParams(sent).map(params => [params.type, params.text, params.key, params.code])).toEqual([
      ['keyDown', '', 'Enter', 'Enter'],
      ['keyUp', '', 'Enter', 'Enter'],
      ['keyDown', '', 'Tab', 'Tab'],
      ['keyUp', '', 'Tab', 'Tab'],
      ['keyDown', '', 'Backspace', 'Backspace'],
      ['keyUp', '', 'Backspace', 'Backspace'],
    ])
  })
})
