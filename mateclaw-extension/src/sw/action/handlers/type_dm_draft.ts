import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { TypeDmDraftParams } from '../types'
import { SessionDetachedError, type DebuggerManager } from '../../debugger-manager'

export interface TypeDmDraftHandlerDeps {
  debugger: DebuggerManager
  chrome?: typeof globalThis.chrome
}

export const typeDmDraftHandler = (
  deps: TypeDmDraftHandlerDeps = {},
): ActionHandler<TypeDmDraftParams> => {
  return async (tabId, params, _deadlineMs) => {
    const text = String(params?.text || '').trim()
    if (!text) {
      throw new ActionFailureError('HANDLER_ERROR', 'type_dm_draft text is required', false)
    }
    const send = params?.send === true
    const sendOnly = params?.sendOnly === true
    const chromeApi = deps.chrome ?? globalThis.chrome
    if (!chromeApi?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting.executeScript is unavailable', true)
    }
    if (sendOnly) {
      const sent = await clickDmSendInPage(chromeApi, tabId, text)
      if (!sent.ok) {
        throw new ActionFailureError(
          'GROUNDING_AMBIGUOUS',
          `dm send failed: ${sent.reason || 'send_button_not_found'}`,
          false,
        )
      }
      return {
        ok: true,
        elapsed_ms: 0,
        payload: {
          draftTyped: true,
          text,
          target: 'dm_existing_draft',
          sent: sent.sent === true,
          sendTarget: sent.target,
        },
      }
    }

    // [出路③] 主路：MAIN world 经 React fiber 拿 Slate editor、模型层写草稿(后台无焦点可用)。
    // 命中即返回；失败记 slateReason 继续走下面的 ISOLATED execCommand / CDP 兜底(仅前台有效)。
    let slateReason = 'not_attempted'
    try {
      const sr = await chromeApi.scripting.executeScript({
        target: { tabId, allFrames: false },
        world: 'MAIN',
        func: typeDmDraftBySlateEditor,
        args: [text],
      })
      const sp = sr?.[0]?.result as { ok?: boolean; draftTyped?: boolean; reason?: string; target?: string } | undefined
      if (sp?.ok === true) {
        const sent = send ? await clickDmSendInPage(chromeApi, tabId, text) : { ok: true, sent: false, target: undefined }
        if (!sent.ok) {
          throw new ActionFailureError('GROUNDING_AMBIGUOUS',
            `dm draft typed (slate) but send failed: ${sent.reason || 'send_button_not_found'}`, false)
        }
        return {
          ok: true,
          elapsed_ms: 0,
          payload: { draftTyped: true, text, target: sp.target || 'slate_editor', sent: sent.sent === true, sendTarget: sent.target },
        }
      }
      slateReason = sp?.reason || 'slate_unknown'
    } catch (e) {
      if (e instanceof ActionFailureError) throw e
      slateReason = 'slate_threw:' + String((e as Error)?.message || e)
    }

    const results = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: typeDouyinDmDraftInPage,
      args: [text, send],
    })
    const payload = results?.[0]?.result as
      | { ok?: boolean; reason?: string; draftTyped?: boolean; sent?: boolean; target?: string; sendTarget?: string }
      | undefined
    if (payload?.draftTyped === true) {
      if (!send || payload.sent === true) {
        return {
          ok: true,
          elapsed_ms: 0,
          payload: {
            draftTyped: true,
            text,
            target: payload.target || 'dm_editable',
            sent: payload.sent === true,
            sendTarget: payload.sendTarget,
          },
        }
      }
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        `dm draft typed but send failed: ${payload.reason || 'dm_send_not_confirmed'}`,
        false,
      )
    }
    if (payload?.ok !== true || payload.draftTyped !== true) {
      const cdp = await typeDmDraftByCdp(deps.debugger, tabId, text)
      if (cdp.ok === true) {
        const sent = send ? await clickDmSendInPage(chromeApi, tabId, text) : { ok: true, sent: false, target: undefined }
        if (!sent.ok) {
          throw new ActionFailureError(
            'GROUNDING_AMBIGUOUS',
            `dm draft typed but send failed: ${sent.reason || 'send_button_not_found'}`,
            false,
          )
        }
        return {
          ok: true,
          elapsed_ms: 0,
          payload: {
            draftTyped: true,
            text,
            target: cdp.target || 'dm_cdp_insert_text',
            sent: sent.sent === true,
            sendTarget: sent.target,
          },
        }
      }
      throw new ActionFailureError(
        'GROUNDING_AMBIGUOUS',
        `slate=${slateReason}; ${payload?.reason || 'dm draft was not observed after typing'}; cdp=${cdp.reason || 'failed'}`,
        false,
      )
    }
    return {
      ok: true,
      elapsed_ms: 0,
      payload: {
        draftTyped: true,
        text,
        target: payload.target || 'dm_editable',
        sent: payload.sent === true,
        sendTarget: payload.sendTarget,
      },
    }
  }
}

async function typeDmDraftByCdp(
  debug: DebuggerManager,
  tabId: number,
  text: string,
): Promise<{ ok: boolean; reason?: string; target?: string }> {
  try {
    const point = await findDmInputClickPoint(tabId)
    if (!point) return { ok: false, reason: 'dm_input_click_point_not_found' }
    await debug.attach(tabId)
    await debug.send(tabId, 'Input.dispatchMouseEvent', {
      type: 'mousePressed',
      x: point.x,
      y: point.y,
      button: 'left',
      clickCount: 1,
      modifiers: 0,
    })
    await debug.send(tabId, 'Input.dispatchMouseEvent', {
      type: 'mouseReleased',
      x: point.x,
      y: point.y,
      button: 'left',
      clickCount: 1,
      modifiers: 0,
    })
    await debug.send(tabId, 'Input.insertText', { text })
    await sleep(180)
    const observed = await draftVisibleInPage(tabId, text)
    return {
      ok: observed,
      reason: observed ? undefined : `draft_not_visible_after_cdp_insert@${Math.round(point.x)},${Math.round(point.y)}`,
      target: `cdp@${Math.round(point.x)},${Math.round(point.y)}`,
    }
  } catch (error) {
    if (error instanceof SessionDetachedError) {
      return { ok: false, reason: error.message }
    }
    return { ok: false, reason: error instanceof Error ? error.message : String(error) }
  }
}

// [出路③ 最后一公里] 后台无焦点向抖音 Slate 私信框输入。必须 world:'MAIN'：Slate editor 对象
// 在页面 realm，ISOLATED world 取到引用也调不动其方法。经 React fiber 拿到 editor，用模型层
// insertText(显式末尾 selection)写入——不依赖 document 焦点(execCommand/CDP Input 后台必失效)。
// 取不到 editor 时退合成 beforeinput(Slate 原生监听 beforeinput、不查 hasFocus/isTrusted)。
async function typeDmDraftBySlateEditor(
  text: string,
): Promise<{ ok: boolean; draftTyped?: boolean; reason?: string; target?: string }> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const panel: any = document.querySelector('#imSaasContainerId, [data-e2e="im-dialog"]') || document
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const el: any = panel.querySelector('[data-slate-editor="true"]')
  if (!el) return { ok: false, reason: 'slate_editable_not_found' }
  const want = String(text).replace(/\s+/g, '')
  const domHas = (): boolean => String(el.innerText || el.textContent || '').replace(/\s+/g, '').includes(want)

  // fiber 取 Slate editor —— 鸭子类型沿 fiber.return 向上遍历，不写死路径(跨 Slate/React 版本会断)
  const key = Object.keys(el).find((k: string) => k.startsWith('__reactFiber$') || k.startsWith('__reactInternalInstance$'))
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let editor: any = null
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  for (let f: any = key ? el[key] : null, i = 0; f && i < 40 && !editor; f = f.return, i++) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const bags: any[] = [f.memoizedProps, f.memoizedState, f.stateNode && f.stateNode.props]
    for (const bag of bags) {
      if (!bag) continue
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const cands: any[] = bag.editor ? [bag.editor] : Object.values(bag)
      for (const v of cands) {
        if (v && typeof v === 'object' && typeof v.insertText === 'function'
          && 'selection' in v && 'children' in v && typeof v.apply === 'function') { editor = v; break }
      }
      if (editor) break
    }
  }

  if (editor) {
    try {
      const endPoint = () => {
        const path: number[] = []
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        let node: any = editor
        while (node && node.children && node.children.length) {
          const idx = node.children.length - 1
          path.push(idx)
          node = node.children[idx]
        }
        const off = node && typeof node.text === 'string' ? node.text.length : 0
        return { anchor: { path, offset: off }, focus: { path, offset: off } }
      }
      // 清空旧内容(模型层，无焦点可用)
      try {
        editor.selection = { anchor: { path: [0, 0], offset: 0 }, focus: endPoint().anchor }
        if (typeof editor.deleteFragment === 'function') editor.deleteFragment()
        else if (typeof editor.delete === 'function') editor.delete()
      } catch { /* ignore clear failure */ }
      // 末尾插入；多行按行拆，行间走 insertSoftBreak/insertBreak
      editor.selection = endPoint()
      const lines = String(text).split('\n')
      for (let i = 0; i < lines.length; i++) {
        if (i > 0) {
          if (typeof editor.insertSoftBreak === 'function') editor.insertSoftBreak()
          else if (typeof editor.insertBreak === 'function') editor.insertBreak()
          else editor.insertText('\n')
        }
        editor.insertText(lines[i])
      }
      if (typeof editor.onChange === 'function') editor.onChange()
      await new Promise(r => setTimeout(r, 150))
      if (domHas()) return { ok: true, draftTyped: true, target: 'slate_editor_main_world' }
    } catch { /* fall through to beforeinput */ }
  }

  // 兜底：合成 beforeinput(同 MAIN world)。Slate 用原生 addEventListener('beforeinput') 监听、
  // 不查 hasFocus/isTrusted；setBaseAndExtent 只做元素级 focus(不需窗口焦点)。
  try {
    el.focus()
    const sel = window.getSelection()
    if (sel) {
      const r = document.createRange()
      r.selectNodeContents(el)
      r.collapse(false)
      sel.removeAllRanges()
      sel.addRange(r)
    }
    el.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, cancelable: true, composed: true, inputType: 'insertText', data: text }))
    el.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: text }))
    await new Promise(r => setTimeout(r, 150))
    if (domHas()) return { ok: true, draftTyped: true, target: 'slate_beforeinput' }
  } catch { /* ignore */ }

  return { ok: false, reason: editor ? 'slate_inserted_but_dom_empty' : 'slate_editor_not_found_on_fiber' }
}

async function typeDouyinDmDraftInPage(
  text: string,
  send: boolean,
): Promise<{ ok: boolean; reason?: string; draftTyped?: boolean; sent?: boolean; target?: string; sendTarget?: string }> {
  if (!/douyin\.com$/u.test(location.hostname) && !location.hostname.endsWith('.douyin.com')) {
    return { ok: false, reason: 'not_douyin_page' }
  }
  // 抖音私信是作者主页上的同页浮层(#imSaasContainerId)，URL 不变；用 DOM 锚点判断浮层是否打开，
  // 而非 innerText(后台/最小化时 innerText 可能残缺、误判 dm_context_not_visible)。
  if (!document.querySelector('#imSaasContainerId, [data-e2e="im-dialog"], .messageEditorinputArea, .e2e-send-msg-btn')) {
    return { ok: false, reason: 'dm_panel_not_open' }
  }
  const target = findDmEditable()
  if (!target) {
    return { ok: false, reason: 'dm_editable_not_found' }
  }
  target.scrollIntoView({ block: 'center', inline: 'center' })
  target.focus()

  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
    const setter = Object.getOwnPropertyDescriptor(
      target instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype,
      'value',
    )?.set
    if (setter) setter.call(target, text)
    else target.value = text
    target.dispatchEvent(new InputEvent('input', {
      bubbles: true,
      composed: true,
      data: text,
      inputType: 'insertReplacementText',
    }))
    target.dispatchEvent(new Event('change', { bubbles: true, composed: true }))
  } else {
    placeCaretAtEnd(target)
    const selected = selectEditableContent(target)
    if (selected) {
      document.execCommand?.('delete', false)
    } else {
      target.textContent = ''
      placeCaretAtEnd(target)
    }
    document.execCommand?.('insertText', false, text)
    if (!(editableText(target).includes(text))) {
      target.textContent = text
      target.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        composed: true,
        data: text,
        inputType: 'insertReplacementText',
      }))
    }
  }

  const typed = editableText(target).includes(text) ||
    findDmEditableText().includes(text) ||
    clean(document.body?.innerText || document.body?.textContent || '').includes(text)
  if (!typed || !send) {
    return {
      ok: typed,
      draftTyped: typed,
      sent: false,
      reason: typed ? undefined : 'draft_text_not_visible_in_editable',
      target: targetDescription(target),
    }
  }
  await sleep(160)
  const sendResult = clickDmSendButton(text, target)
  if (!sendResult.ok) {
    return {
      ok: false,
      draftTyped: true,
      sent: false,
      reason: sendResult.reason,
      target: targetDescription(target),
      sendTarget: sendResult.target,
    }
  }
  await sleep(260)
  const sent = !draftStillVisibleInEditable(text)
  return {
    ok: sent,
    draftTyped: true,
    sent,
    reason: sent ? undefined : 'dm_send_not_confirmed_after_click',
    target: targetDescription(target),
    sendTarget: sendResult.target,
  }
}

async function clickDmSendInPage(
  chromeApi: typeof globalThis.chrome,
  tabId: number,
  text: string,
): Promise<{ ok: boolean; sent?: boolean; target?: string; reason?: string }> {
  const [result] = await chromeApi.scripting.executeScript({
    target: { tabId, allFrames: false },
    func: async (draft: string) => {
      const clean = (value: string) => String(value || '').replace(/\s+/g, '')
      const elementText = (el: HTMLElement) =>
        `${el.getAttribute('placeholder') || ''} ${el.getAttribute('aria-label') || ''} ${el.getAttribute('title') || ''} ${el.innerText || el.textContent || ''}`.replace(/\s+/g, '')
      const editableText = (el: HTMLElement) => {
        if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return el.value || ''
        return el.innerText || el.textContent || ''
      }
      const isEditable = (el: HTMLElement) =>
        el instanceof HTMLInputElement ||
        el instanceof HTMLTextAreaElement ||
        el.isContentEditable ||
        (el.getAttribute('role') || '').toLowerCase() === 'textbox' ||
        el.getAttribute('data-slate-editor') === 'true' ||
        el.classList.contains('ProseMirror')
      const editableRoot = (el: HTMLElement): HTMLElement | null => {
        if (isEditable(el)) return el
        const closest = el.closest<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
        if (closest && isEditable(closest)) return closest
        const nested = el.querySelector<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
        return nested && isEditable(nested) ? nested : null
      }
      const findEditable = () => {
        const selectors = [
          'textarea',
          'input',
          '[contenteditable="true"]',
          '[contenteditable=""]',
          '[contenteditable="plaintext-only"]',
          '[role="textbox"]',
          '[data-slate-editor="true"]',
          '.ProseMirror',
          '[placeholder*="消息"]',
          '[placeholder*="私信"]',
          '[placeholder*="发送"]',
          '[aria-label*="消息"]',
          '[aria-label*="私信"]',
        ].join(',')
        const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
        const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
        return Array.from(document.querySelectorAll<HTMLElement>(selectors))
          .map((el, index) => {
            const root = editableRoot(el)
            const rect = (root ?? el).getBoundingClientRect()
            return { el: root, index, rect, text: root ? elementText(root) : '' }
          })
          .filter((item): item is { el: HTMLElement; index: number; rect: DOMRect; text: string } => item.el !== null)
          .filter(item => item.rect.width > 0 && item.rect.height > 0)
          .filter(item => item.rect.top >= Math.max(80, viewportH * 0.22))
          .filter(item => item.rect.left >= viewportW * 0.52)
          .filter(item => !item.text.includes('搜索'))
          .sort((a, b) => b.rect.top - a.rect.top || a.index - b.index)[0]?.el ?? null
      }
      const allEditableText = () => Array.from(document.querySelectorAll<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror'))
        .map(editableText)
        .join('\n')
      const isDisabled = (el: HTMLElement) =>
        el.getAttribute('aria-disabled') === 'true' ||
        el.getAttribute('disabled') === 'true' ||
        (el instanceof HTMLButtonElement && el.disabled)
      const sendSelector = '.e2e-send-msg-btn,.messageMsgInputpublishRedBtn,.messageMsgInputpublishBtn'
      const explicitActionSelector = 'button,[role="button"],[aria-label*="发送"],[title*="发送"],div[tabindex],span[tabindex],label'
      const hasClassToken = (el: Element, pattern: RegExp) => {
        const names = [
          String(el.getAttribute('class') || ''),
          String((el as HTMLElement).className || ''),
          String(el.parentElement?.getAttribute('class') || ''),
          String((el.parentElement as HTMLElement | null)?.className || ''),
        ].join(' ')
        return pattern.test(names)
      }
      const actionRoot = (el: HTMLElement): HTMLElement => {
        const explicitSend = el.closest<HTMLElement>(sendSelector)
        if (explicitSend) return explicitSend
        const explicitAction = el.closest<HTMLElement>(explicitActionSelector)
        if (explicitAction) return explicitAction
        const svg = el.closest<HTMLElement>('svg')
        return svg ?? el
      }
      const uniqueActionItems = (root: ParentNode, selectors: string) => {
        const seen = new Set<HTMLElement>()
        return Array.from(root.querySelectorAll<HTMLElement>(selectors))
          .map((el, index) => ({ el: actionRoot(el), index }))
          .filter(item => {
            if (seen.has(item.el)) return false
            seen.add(item.el)
            return true
          })
          .map(item => ({ ...item, rect: item.el.getBoundingClientRect(), text: elementText(item.el) }))
      }
      const isAttachmentControl = (el: HTMLElement, text: string) => {
        const normalized = clean(text)
        if (/上传|文件|图片|照片|相册|附件|选择文件|image|file|upload/u.test(normalized)) return true
        if (hasClassToken(el, /semi-upload|upload|file|attach/i)) return true
        if (el instanceof HTMLInputElement && el.type === 'file') return true
        if (el.querySelector('input[type="file"]')) return true
        const label = el.closest('label')
        return !!label?.querySelector('input[type="file"]')
      }
      const likelySendText = (text: string) => {
        const normalized = clean(text)
        return normalized === '发送' ||
          normalized === 'Send' ||
          (/发送/u.test(normalized) && !/发送消息|输入消息|发送一条文字消息|对方回复|关闭会话|消息/u.test(normalized))
      }
      const colorNumbers = (value: string) => {
        const match = String(value || '').match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i)
        return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null
      }
      const sendAccent = (value: string) => {
        const rgb = colorNumbers(value)
        return !!rgb && rgb[0] >= 220 && rgb[1] <= 95 && rgb[2] >= 65 && rgb[2] <= 150
      }
      const hasSendAccent = (el: HTMLElement) => {
        if (hasClassToken(el, /e2e-send-msg-btn|messageMsgInputpublishRedBtn|publishRedBtn/i)) return true
        const candidates = [el, el.parentElement, el.closest<HTMLElement>('button,[role="button"],div[tabindex],span[tabindex]')]
          .filter((candidate): candidate is HTMLElement => !!candidate)
        return candidates.some(candidate => {
          const style = getComputedStyle(candidate)
          return sendAccent(style.backgroundColor) || sendAccent(style.color) || sendAccent(style.borderColor)
        })
      }
      const nearEditable = (rect: DOMRect, editableRect: DOMRect) => {
        const verticalOverlap = rect.top <= editableRect.bottom + 44 && rect.bottom >= editableRect.top - 44
        const rightOfEditable = rect.left >= editableRect.right - 120 || rect.right >= editableRect.right - 24
        const plausibleSize = rect.width >= 24 && rect.width <= 140 && rect.height >= 24 && rect.height <= 80
        return verticalOverlap && rightOfEditable && plausibleSize
      }
      const centerX = (rect: DOMRect) => rect.left + rect.width / 2
      const centerY = (rect: DOMRect) => rect.top + rect.height / 2
      const sameRow = (rect: DOMRect, editableRect: DOMRect) =>
        centerY(rect) >= editableRect.top - 8 && centerY(rect) <= editableRect.bottom + 8
      const isCompactIcon = (rect: DOMRect) =>
        rect.width >= 18 && rect.width <= 72 && rect.height >= 18 && rect.height <= 72
      const actionSelectors = `${explicitActionSelector},${sendSelector},svg,path`
      const findComposerRoot = (editable: HTMLElement) => {
        const editableRect = editable.getBoundingClientRect()
        let current = editable.parentElement
        for (let depth = 0; current && current !== document.body && depth < 8; depth += 1, current = current.parentElement) {
          const rect = current.getBoundingClientRect()
          if (rect.width <= editableRect.width + 80 || rect.height <= 0 || rect.height > 160) continue
          if (rect.top > editableRect.top + 16 || rect.bottom < editableRect.bottom - 16) continue
          const rightActions = uniqueActionItems(current, actionSelectors)
            .filter(item => item.el !== editable && !editable.contains(item.el))
            .filter(item => item.rect.width > 0 && item.rect.height > 0)
            .filter(item => sameRow(item.rect, editableRect))
            .filter(item => centerX(item.rect) >= editableRect.right - 8)
            .filter(item => !isAttachmentControl(item.el, item.text))
          if (rightActions.length > 0) return current
        }
        return null
      }
      let composer: HTMLElement | null = null
      const rightmostComposerAction = (rect: DOMRect, editableRect: DOMRect) => {
        const composerRect = composer?.getBoundingClientRect()
        if (!composerRect) return false
        const rightBand = Math.max(64, Math.min(110, composerRect.width * 0.18))
        return sameRow(rect, editableRect) &&
          centerX(rect) >= editableRect.right - 8 &&
          centerX(rect) >= composerRect.right - rightBand
      }
      const iconOnlySend = (el: HTMLElement, rect: DOMRect, editableRect: DOMRect, text: string) =>
        !clean(text) && rightmostComposerAction(rect, editableRect) && hasSendAccent(el)
      const pickStructuralSend = (
        rawItems: Array<{ el: HTMLElement; rect: DOMRect; text: string }>,
        safeItems: Array<{ el: HTMLElement; rect: DOMRect; text: string }>,
        editableRect: DOMRect,
      ) => {
        const rawRowActions = rawItems
          .filter(item => sameRow(item.rect, editableRect))
          .filter(item => centerX(item.rect) >= editableRect.right - 8)
          .filter(item => isCompactIcon(item.rect))
          .sort((a, b) => centerX(b.rect) - centerX(a.rect))
        if (rawRowActions.length < 2) return null
        const safeRowActions = safeItems
          .filter(item => rawRowActions.some(raw => raw.el === item.el))
          .sort((a, b) => centerX(b.rect) - centerX(a.rect))
        const rightmostRaw = rawRowActions[0]
        const rightmostSafe = safeRowActions[0]
        const secondRaw = rawRowActions[1]
        if (!rightmostRaw || !rightmostSafe || rightmostRaw.el !== rightmostSafe.el || !secondRaw) return null
        if (centerX(rightmostRaw.rect) - centerX(secondRaw.rect) < 12) return null
        if (!rightmostComposerAction(rightmostRaw.rect, editableRect)) return null
        if (clean(rightmostRaw.text)) return null
        return rightmostRaw.el
      }
      const score = (item: { el: HTMLElement; rect: DOMRect; text: string }, editableRect: DOMRect) => {
        let value = 0
        const role = (item.el.getAttribute('role') || item.el.tagName || '').toLowerCase()
        const normalized = clean(item.text)
        if (role.includes('button')) value += 120
        if (normalized === '发送' || normalized === 'Send') value += 140
        if (/发送/u.test(normalized)) value += 80
        if (composer?.contains(item.el)) value += 120
        if (iconOnlySend(item.el, item.rect, editableRect, item.text)) value += 180
        if (rightmostComposerAction(item.rect, editableRect)) value += 120
        if (nearEditable(item.rect, editableRect)) value += 40
        value += Math.max(0, centerX(item.rect) - editableRect.right) / 10
        if (/搜索|关闭会话|回关|发送消息|输入消息|对方回复/u.test(normalized)) value -= 240
        if (isAttachmentControl(item.el, item.text)) value -= 500
        return value
      }
      const click = (el: HTMLElement) => {
        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true }))
        el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, composed: true }))
        const nativeClick = (el as HTMLElement & { click?: () => void }).click
        if (typeof nativeClick === 'function') nativeClick.call(el)
        else el.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true }))
      }
      await new Promise<void>(resolve => setTimeout(resolve, 160))
      const editable = findEditable()
      if (!editable) return { ok: false, reason: 'dm_editable_not_found_before_send' }
      composer = findComposerRoot(editable)
      const wanted = clean(draft)
      if (!clean(allEditableText()).includes(wanted) && !clean(editableText(editable)).includes(wanted)) {
        return { ok: false, reason: 'draft_not_visible_before_send' }
      }
      const editableRect = editable.getBoundingClientRect()
      const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
      const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
      const selectors = `${explicitActionSelector},${sendSelector},svg,path`
      const buttonRoot: ParentNode = composer ?? document
      const rawItems = uniqueActionItems(buttonRoot, selectors)
        .filter(item => item.rect.width > 0 && item.rect.height > 0)
        .filter(item => item.rect.left >= viewportW * 0.45)
        .filter(item => item.rect.top >= Math.max(120, viewportH * 0.32))
        .filter(item => !isDisabled(item.el))
        .filter(item => composer?.contains(item.el) || likelySendText(item.text))
      const safeItems = rawItems.filter(item => !isAttachmentControl(item.el, item.text))
      const structuralSend = pickStructuralSend(rawItems, safeItems, editableRect)
      const button = safeItems
        .filter(item => likelySendText(item.text) || iconOnlySend(item.el, item.rect, editableRect, item.text) || item.el === structuralSend)
        .sort((a, b) => score(b, editableRect) - score(a, editableRect) || a.index - b.index)[0]?.el ?? null
      if (!button) return { ok: false, reason: 'dm_send_button_not_found' }
      button.scrollIntoView({ block: 'center', inline: 'center' })
      click(button)
      await new Promise<void>(resolve => setTimeout(resolve, 260))
      const sent = !clean(allEditableText()).includes(wanted)
      return {
        ok: sent,
        sent,
        target: button.tagName.toLowerCase(),
        reason: sent ? undefined : 'dm_send_not_confirmed_after_click',
      }
    },
    args: [text],
  })
  const payload = result?.result as { ok?: boolean; sent?: boolean; target?: string; reason?: string } | undefined
  return {
    ok: payload?.ok === true,
    sent: payload?.sent === true,
    target: payload?.target,
    reason: payload?.reason,
  }
}

function clickDmSendButton(
  text: string,
  editable?: HTMLElement | null,
): { ok: boolean; sent?: boolean; target?: string; reason?: string } {
  if (!/douyin\.com$/u.test(location.hostname) && !location.hostname.endsWith('.douyin.com')) {
    return { ok: false, reason: 'not_douyin_page' }
  }
  const wanted = clean(text)
  if (!wanted) return { ok: false, reason: 'empty_draft' }
  const currentEditable = editable ?? findDmEditable()
  if (!currentEditable) return { ok: false, reason: 'dm_editable_not_found_before_send' }
  if (!clean(findDmEditableText()).includes(wanted) && !clean(editableText(currentEditable)).includes(wanted)) {
    return { ok: false, reason: 'draft_not_visible_before_send' }
  }
  const button = findDmSendButton(currentEditable)
  if (!button) return { ok: false, reason: 'dm_send_button_not_found' }
  button.scrollIntoView({ block: 'center', inline: 'center' })
  clickElement(button)
  const sendTarget = targetDescription(button)
  return {
    ok: true,
    sent: false,
    target: sendTarget,
  }
}

function draftStillVisibleInEditable(text: string): boolean {
  const wanted = clean(text)
  return !!wanted && clean(findDmEditableText()).includes(wanted)
}

function findDmSendButton(editable: HTMLElement): HTMLElement | null {
  const editableRect = editable.getBoundingClientRect()
  const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
  const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
  const composer = findDmComposerRoot(editable)
  const searchRoot: ParentNode = composer ?? document
  const rawItems = uniqueDmActionItems(searchRoot)
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .filter(item => item.rect.left >= viewportW * 0.45)
    .filter(item => item.rect.top >= Math.max(120, viewportH * 0.32))
    .filter(item => !isDisabled(item.el))
    .filter(item => composer?.contains(item.el) || isLikelySendButtonText(item.text))
  const safeItems = rawItems.filter(item => !isAttachmentLikeControl(item.el, item.text))
  const structuralSend = pickStructuralDmSend(rawItems, safeItems, editableRect, composer)
  return safeItems
    .filter(item => isLikelySendButtonText(item.text) || isIconOnlySendButton(item.el, item.rect, editableRect, item.text, composer) || item.el === structuralSend)
    .sort((a, b) => scoreSendButton(b, editableRect, composer) - scoreSendButton(a, editableRect, composer) || a.index - b.index)[0]?.el ?? null
}

function isLikelySendButtonText(text: string): boolean {
  const normalized = clean(text)
  return normalized === '发送'
    || normalized === 'Send'
    || (/发送/u.test(normalized)
      && !/发送消息|输入消息|发送一条文字消息|对方回复|关闭会话|消息/u.test(normalized))
}

function isNearEditableSendControl(rect: DOMRect, editableRect: DOMRect): boolean {
  const verticalOverlap = rect.top <= editableRect.bottom + 44 && rect.bottom >= editableRect.top - 44
  const rightOfEditable = rect.left >= editableRect.right - 120 || rect.right >= editableRect.right - 24
  const plausibleSize = rect.width >= 24 && rect.width <= 140 && rect.height >= 24 && rect.height <= 80
  return verticalOverlap && rightOfEditable && plausibleSize
}

function isIconOnlySendButton(
  el: HTMLElement,
  rect: DOMRect,
  editableRect: DOMRect,
  text: string,
  composer: HTMLElement | null,
): boolean {
  return !clean(text)
    && isRightmostComposerAction(rect, editableRect, composer)
    && hasSendAccent(el)
}

function findDmComposerRoot(editable: HTMLElement): HTMLElement | null {
  const editableRect = editable.getBoundingClientRect()
  let current = editable.parentElement
  for (let depth = 0; current && current !== document.body && depth < 8; depth += 1, current = current.parentElement) {
    const rect = current.getBoundingClientRect()
    if (rect.width <= editableRect.width + 80 || rect.height <= 0 || rect.height > 160) continue
    if (rect.top > editableRect.top + 16 || rect.bottom < editableRect.bottom - 16) continue
    const rightActions = uniqueDmActionItems(current)
      .filter(item => item.el !== editable && !editable.contains(item.el))
      .filter(item => item.rect.width > 0 && item.rect.height > 0)
      .filter(item => sameComposerRow(item.rect, editableRect))
      .filter(item => rectCenterX(item.rect) >= editableRect.right - 8)
      .filter(item => !isAttachmentLikeControl(item.el, item.text))
    if (rightActions.length > 0) {
      return current
    }
  }
  return null
}

function dmSendSelector(): string {
  return '.e2e-send-msg-btn,.messageMsgInputpublishRedBtn,.messageMsgInputpublishBtn'
}

function dmExplicitActionSelector(): string {
  return 'button,[role="button"],[aria-label*="发送"],[title*="发送"],div[tabindex],span[tabindex],label'
}

function dmActionSelector(): string {
  return `${dmExplicitActionSelector()},${dmSendSelector()},svg,path`
}

function dmActionRoot(el: HTMLElement): HTMLElement {
  const explicitSend = el.closest<HTMLElement>(dmSendSelector())
  if (explicitSend) return explicitSend
  const explicitAction = el.closest<HTMLElement>(dmExplicitActionSelector())
  if (explicitAction) return explicitAction
  const svg = el.closest<HTMLElement>('svg')
  return svg ?? el
}

function uniqueDmActionItems(root: ParentNode): Array<{ el: HTMLElement; index: number; rect: DOMRect; text: string }> {
  const seen = new Set<HTMLElement>()
  return Array.from(root.querySelectorAll<HTMLElement>(dmActionSelector()))
    .map((el, index) => ({ el: dmActionRoot(el), index }))
    .filter(item => {
      if (seen.has(item.el)) return false
      seen.add(item.el)
      return true
    })
    .map(item => ({ ...item, rect: item.el.getBoundingClientRect(), text: elementText(item.el) }))
}

function pickStructuralDmSend(
  rawItems: Array<{ el: HTMLElement; rect: DOMRect; text: string }>,
  safeItems: Array<{ el: HTMLElement; rect: DOMRect; text: string }>,
  editableRect: DOMRect,
  composer: HTMLElement | null,
): HTMLElement | null {
  const rawRowActions = rawItems
    .filter(item => sameComposerRow(item.rect, editableRect))
    .filter(item => rectCenterX(item.rect) >= editableRect.right - 8)
    .filter(item => isCompactDmIcon(item.rect))
    .sort((a, b) => rectCenterX(b.rect) - rectCenterX(a.rect))
  if (rawRowActions.length < 2) return null
  const safeRowActions = safeItems
    .filter(item => rawRowActions.some(raw => raw.el === item.el))
    .sort((a, b) => rectCenterX(b.rect) - rectCenterX(a.rect))
  const rightmostRaw = rawRowActions[0]
  const secondRaw = rawRowActions[1]
  const rightmostSafe = safeRowActions[0]
  if (!rightmostRaw || !secondRaw || !rightmostSafe || rightmostRaw.el !== rightmostSafe.el) return null
  if (rectCenterX(rightmostRaw.rect) - rectCenterX(secondRaw.rect) < 12) return null
  if (!isRightmostComposerAction(rightmostRaw.rect, editableRect, composer)) return null
  if (clean(rightmostRaw.text)) return null
  return rightmostRaw.el
}

function isCompactDmIcon(rect: DOMRect): boolean {
  return rect.width >= 18 && rect.width <= 72 && rect.height >= 18 && rect.height <= 72
}

function isRightmostComposerAction(rect: DOMRect, editableRect: DOMRect, composer: HTMLElement | null): boolean {
  if (!composer) return false
  const composerRect = composer.getBoundingClientRect()
  const rightBand = Math.max(64, Math.min(110, composerRect.width * 0.18))
  return sameComposerRow(rect, editableRect)
    && rectCenterX(rect) >= editableRect.right - 8
    && rectCenterX(rect) >= composerRect.right - rightBand
}

function sameComposerRow(rect: DOMRect, editableRect: DOMRect): boolean {
  const y = rectCenterY(rect)
  return y >= editableRect.top - 8 && y <= editableRect.bottom + 8
}

function rectCenterX(rect: DOMRect): number {
  return rect.left + rect.width / 2
}

function rectCenterY(rect: DOMRect): number {
  return rect.top + rect.height / 2
}

function isAttachmentLikeControl(el: HTMLElement, text: string): boolean {
  const normalized = clean(text)
  if (/上传|文件|图片|照片|相册|附件|选择文件|image|file|upload/u.test(normalized)) return true
  if (hasClassToken(el, /semi-upload|upload|file|attach/i)) return true
  if (el instanceof HTMLInputElement && el.type === 'file') return true
  if (el.querySelector('input[type="file"]')) return true
  const label = el.closest('label')
  return !!label?.querySelector('input[type="file"]')
}

function hasSendAccent(el: HTMLElement): boolean {
  if (hasClassToken(el, /e2e-send-msg-btn|messageMsgInputpublishRedBtn|publishRedBtn/i)) return true
  const candidates = [el, el.parentElement, el.closest<HTMLElement>('button,[role="button"],div[tabindex],span[tabindex]')]
    .filter((candidate): candidate is HTMLElement => !!candidate)
  return candidates.some(candidate => {
    const style = getComputedStyle(candidate)
    return colorLooksLikeDouyinSend(style.backgroundColor)
      || colorLooksLikeDouyinSend(style.color)
      || colorLooksLikeDouyinSend(style.borderColor)
  })
}

function hasClassToken(el: Element, pattern: RegExp): boolean {
  const names = [
    String(el.getAttribute('class') || ''),
    String((el as HTMLElement).className || ''),
    String(el.parentElement?.getAttribute('class') || ''),
    String((el.parentElement as HTMLElement | null)?.className || ''),
  ].join(' ')
  return pattern.test(names)
}

function colorLooksLikeDouyinSend(value: string): boolean {
  const match = String(value || '').match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i)
  if (!match) return false
  const red = Number(match[1])
  const green = Number(match[2])
  const blue = Number(match[3])
  return red >= 220 && green <= 95 && blue >= 65 && blue <= 150
}

function scoreSendButton(
  item: { el: HTMLElement; rect: DOMRect; text: string },
  editableRect: DOMRect,
  composer: HTMLElement | null,
): number {
  let score = 0
  const role = (item.el.getAttribute('role') || item.el.tagName || '').toLowerCase()
  const text = clean(item.text)
  if (role.includes('button')) score += 120
  if (text === '发送' || text === 'Send') score += 140
  if (/发送/u.test(text)) score += 80
  if (isIconOnlySendButton(item.el, item.rect, editableRect, item.text, composer)) score += 180
  if (composer?.contains(item.el)) score += 120
  if (isRightmostComposerAction(item.rect, editableRect, composer)) score += 120
  if (isNearEditableSendControl(item.rect, editableRect)) score += 40
  score += Math.max(0, rectCenterX(item.rect) - editableRect.right) / 10
  if (/搜索|关闭会话|回关|发送消息|输入消息|对方回复/u.test(text)) score -= 240
  if (isAttachmentLikeControl(item.el, item.text)) score -= 500
  return score
}

function clickElement(el: HTMLElement): void {
  if (typeof PointerEvent === 'function') {
    el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }))
  }
  el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true }))
  if (typeof PointerEvent === 'function') {
    el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, composed: true }))
  }
  el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, composed: true }))
  const nativeClick = (el as HTMLElement & { click?: () => void }).click
  if (typeof nativeClick === 'function') nativeClick.call(el)
  else el.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true }))
}

function isDisabled(el: HTMLElement): boolean {
  return el.getAttribute('aria-disabled') === 'true'
    || el.getAttribute('disabled') === 'true'
    || (el instanceof HTMLButtonElement && el.disabled)
}

function findDmEditable(): HTMLElement | null {
  // 优先：私信浮层(#imSaasContainerId / im-dialog)内的输入框，不按视口 rect 过滤——后台/最小化时
  // 视口塌缩、rect 不可靠，但浮层 + 输入框的 DOM 锚点稳定。
  const dmPanel = document.querySelector<HTMLElement>('#imSaasContainerId, [data-e2e="im-dialog"]')
  if (dmPanel) {
    const anchored = dmPanel.querySelector<HTMLElement>(
      '.messageEditorinputArea [contenteditable], .messageEditorinputArea [data-slate-editor="true"], .messageEditorinputArea, [class*="messageEditor"] [contenteditable], [data-slate-editor="true"], [contenteditable="true"]',
    )
    const root = anchored ? editableRoot(anchored) : null
    if (root && isEditable(root) && !elementText(root).includes('搜索')) return root
  }
  const selectors = [
    'textarea',
    'input',
    '[contenteditable="true"]',
    '[contenteditable=""]',
    '[contenteditable="plaintext-only"]',
    '[role="textbox"]',
    '[data-slate-editor="true"]',
    '.ProseMirror',
    '.DraftEditor-editorContainer [contenteditable]',
    '[placeholder*="消息"]',
    '[placeholder*="私信"]',
    '[placeholder*="发送"]',
    '[aria-label*="消息"]',
    '[aria-label*="私信"]',
  ].join(',')
  const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
  const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
  return Array.from(document.querySelectorAll<HTMLElement>(selectors))
    .map((el, index) => ({ el, index, rect: el.getBoundingClientRect(), text: elementText(el) }))
    .map(item => {
      const editable = editableRoot(item.el)
      return editable
        ? { ...item, el: editable, rect: editable.getBoundingClientRect(), text: elementText(editable) }
        : { ...item, el: null }
    })
    .filter((item): item is { el: HTMLElement; index: number; rect: DOMRect; text: string } => item.el !== null)
    .filter(item => isEditable(item.el))
    .filter(item => item.rect.width > 0 && item.rect.height > 0)
    .filter(item => item.rect.top >= Math.max(80, viewportH * 0.22))
    .filter(item => item.rect.left >= viewportW * 0.52)
    .filter(item => !item.text.includes('搜索'))
    .sort((a, b) => scoreEditable(b) - scoreEditable(a) || a.index - b.index)[0]?.el ?? null
}

function editableRoot(el: HTMLElement): HTMLElement | null {
  if (isEditable(el)) return el
  const closest = el.closest<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
  if (closest && isEditable(closest)) return closest
  const nested = el.querySelector<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror')
  return nested && isEditable(nested) ? nested : null
}

function isEditable(el: HTMLElement): boolean {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return true
  if (el.isContentEditable) return true
  if ((el.getAttribute('role') || '').toLowerCase() === 'textbox') return true
  if (el.getAttribute('data-slate-editor') === 'true') return true
  return el.classList.contains('ProseMirror')
}

function scoreEditable(item: { el: HTMLElement; rect: DOMRect; text: string }): number {
  let score = 0
  const marker = elementText(item.el)
  if (item.el instanceof HTMLTextAreaElement) score += 120
  if (item.el.isContentEditable) score += 110
  if ((item.el.getAttribute('role') || '').toLowerCase() === 'textbox') score += 80
  if (/消息|私信|发送/u.test(marker)) score += 100
  if (item.rect.top > (window.innerHeight || 1) * 0.55) score += 60
  if (item.rect.width > 160) score += 30
  return score
}

function findDmEditableText(): string {
  return Array.from(document.querySelectorAll<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror'))
    .map(editableText)
    .join('\n')
}

function editableText(el: HTMLElement): string {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    return el.value || ''
  }
  return el.innerText || el.textContent || ''
}

function elementText(el: HTMLElement): string {
  return `${el.getAttribute('placeholder') || ''} ${el.getAttribute('aria-label') || ''} ${el.getAttribute('title') || ''} ${el.innerText || el.textContent || ''}`.replace(/\s+/g, '')
}

function selectEditableContent(el: HTMLElement): boolean {
  if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
    el.select()
    return true
  }
  const selection = window.getSelection()
  if (!selection) return false
  const range = document.createRange()
  range.selectNodeContents(el)
  selection.removeAllRanges()
  selection.addRange(range)
  return true
}

function placeCaretAtEnd(el: HTMLElement): void {
  el.focus()
  const selection = window.getSelection()
  if (!selection || el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return
  const range = document.createRange()
  range.selectNodeContents(el)
  range.collapse(false)
  selection.removeAllRanges()
  selection.addRange(range)
}

function clean(text: string): string {
  return String(text || '').replace(/\s+/g, '')
}

function targetDescription(el: HTMLElement): string {
  return `${el.tagName.toLowerCase()}${el.getAttribute('role') ? `[role=${el.getAttribute('role')}]` : ''}`
}

async function findDmInputClickPoint(tabId: number): Promise<{ x: number; y: number } | null> {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId, allFrames: false },
    func: () => {
      if (!/douyin\.com$/u.test(location.hostname) && !location.hostname.endsWith('.douyin.com')) return null
      const viewportW = window.innerWidth || document.documentElement.clientWidth || 1
      const viewportH = window.innerHeight || document.documentElement.clientHeight || 1
      const selectors = [
        'textarea',
        'input',
        '[contenteditable="true"]',
        '[contenteditable=""]',
        '[contenteditable="plaintext-only"]',
        '[role="textbox"]',
        '[data-slate-editor="true"]',
        '.ProseMirror',
        '[placeholder*="消息"]',
        '[placeholder*="私信"]',
        '[placeholder*="发送"]',
        '[aria-label*="消息"]',
        '[aria-label*="私信"]',
        'div',
      ].join(',')
      const candidates = Array.from(document.querySelectorAll<HTMLElement>(selectors))
        .map((el, index) => {
          const rect = el.getBoundingClientRect()
          const text = `${el.getAttribute('placeholder') || ''} ${el.getAttribute('aria-label') || ''} ${el.innerText || el.textContent || ''}`.replace(/\s+/g, '')
          const editable = el instanceof HTMLInputElement ||
            el instanceof HTMLTextAreaElement ||
            el.isContentEditable ||
            (el.getAttribute('role') || '').toLowerCase() === 'textbox' ||
            el.getAttribute('data-slate-editor') === 'true' ||
            el.classList.contains('ProseMirror')
          let score = 0
          if (editable) score += 200
          if (/消息|私信|发送|请输入|聊/u.test(text)) score += 160
          if (rect.left >= viewportW * 0.52) score += 90
          if (rect.top >= viewportH * 0.70) score += 90
          if (rect.width >= 240 && rect.height >= 28 && rect.height <= 160) score += 70
          if (text.includes('搜索') || text.includes('关闭会话') || text.includes('回关')) score -= 300
          return { rect, index, score }
        })
        .filter(item => item.rect.width > 0 && item.rect.height > 0)
        .filter(item => item.rect.left >= viewportW * 0.52)
        .filter(item => item.rect.top >= viewportH * 0.55)
        .filter(item => item.score > 0)
        .sort((a, b) => b.score - a.score || b.rect.top - a.rect.top || a.index - b.index)
      const best = candidates[0]
      if (best) {
        return {
          x: best.rect.left + Math.min(best.rect.width * 0.5, best.rect.width - 24),
          y: best.rect.top + Math.min(best.rect.height * 0.5, best.rect.height - 16),
        }
      }
      return { x: viewportW * 0.84, y: viewportH - 42 }
    },
  })
  const point = result?.result as { x?: number; y?: number } | null | undefined
  return typeof point?.x === 'number' && typeof point?.y === 'number' ? { x: point.x, y: point.y } : null
}

async function draftVisibleInPage(tabId: number, text: string): Promise<boolean> {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId, allFrames: false },
    func: (draft: string) => {
      const clean = (value: string) => String(value || '').replace(/\s+/g, '')
      const wanted = clean(draft)
      const editableText = Array.from(document.querySelectorAll<HTMLElement>('textarea, input, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], [role="textbox"], [data-slate-editor="true"], .ProseMirror'))
        .map(el => {
          if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return el.value || ''
          return el.innerText || el.textContent || ''
        })
        .join('\n')
      return clean(editableText).includes(wanted) || clean(document.body?.innerText || document.body?.textContent || '').includes(wanted)
    },
    args: [text],
  })
  return result?.result === true
}

async function sleep(ms: number): Promise<void> {
  await new Promise<void>(resolve => setTimeout(resolve, Math.max(0, ms)))
}
