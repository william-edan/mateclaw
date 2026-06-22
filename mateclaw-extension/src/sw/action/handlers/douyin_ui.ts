import { ActionFailureError, type ActionHandler } from '../ActionExecutor'
import type { DouyinUiParams } from '../types'
import { ensureVisibilityOverride } from './visibility-keepalive'

export interface DouyinUiHandlerDeps {
  /** Chrome API; injectable for tests. Defaults to global chrome. */
  chrome?: typeof globalThis.chrome
}

interface DouyinUiResult {
  ok: boolean
  op: string
  detail: string
}

/**
 * douyin_ui — background-capable, in-page DOM driver for the Douyin steps that
 * a plain CDP click/key can't do off-screen:
 *   - op='sort': synthetic-HOVER the 筛选 trigger to reveal its (JS-driven) sort
 *     panel, then click the requested option (最多点赞 / 最新发布 / 综合排序).
 *     A plain click does NOT open this panel — it needs pointerenter/mouseenter.
 *   - op='open_comments': click the comment icon ([data-e2e="feed-comment-icon"])
 *     to open the comment panel (replaces the foreground-only 'x' shortcut).
 *   - op='pause': pause all <video> elements.
 *   - op='next_video': switch to the next feed video via synthetic ArrowDown
 *     (DOM keydown), confirming the URL video id changed.
 *     Returns {ok:changed, op, detail}; works off-screen, no CDP ArrowDown needed.
 * All via chrome.scripting.executeScript — works on a background / minimised tab.
 */
export const douyinUiHandler = (deps: DouyinUiHandlerDeps): ActionHandler<DouyinUiParams> => {
  return async (tabId, params) => {
    const api = deps.chrome ?? globalThis.chrome
    if (!api?.scripting?.executeScript) {
      throw new ActionFailureError('HANDLER_ERROR', 'chrome.scripting unavailable', true)
    }
    // 后台保活:让页面以为自己可见(每次导航后 document 重载会失效,故每步重注入)
    await ensureVisibilityOverride(api, tabId)
    const op = params?.op ?? ''
    const label = params?.label ?? ''

    let result: DouyinUiResult | undefined
    try {
      const results = await api.scripting.executeScript({
        target: { tabId, allFrames: false },
        func: douyinUiInPage,
        args: [op, label],
      })
      result = results?.[0]?.result as DouyinUiResult | undefined
    } catch (e) {
      throw new ActionFailureError('HANDLER_ERROR', 'douyin_ui inject failed: ' + (e instanceof Error ? e.message : String(e)), true)
    }

    if (!result || !result.ok) {
      throw new ActionFailureError('HANDLER_ERROR', 'douyin_ui(' + op + '): ' + (result?.detail || 'failed'), true)
    }
    return { ok: true, elapsed_ms: 0, payload: { ...result } }
  }
}

/** Injected page func. Pure DOM. */
async function douyinUiInPage(op: string, label: string): Promise<DouyinUiResult> {
  const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms))
  const vis = (e: Element | null | undefined): e is HTMLElement => {
    if (!(e instanceof HTMLElement)) return false
    const b = e.getBoundingClientRect()
    return b.width > 0 && b.height > 0
  }
  const norm = (s: string | null | undefined) => (s || '').replace(/\s+/g, '')
  const center = (el: HTMLElement) => { const r = el.getBoundingClientRect(); return { clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 } }
  const byText = (t: string): HTMLElement | undefined =>
    Array.from(document.querySelectorAll<HTMLElement>('span,div,button,li,[role="button"],[role="menuitem"]'))
      .filter(e => norm(e.textContent) === t && vis(e))
      .sort((a, b) => { const A = a.getBoundingClientRect(), B = b.getBoundingClientRect(); return A.width * A.height - B.width * B.height })[0]
  const click = (el: HTMLElement) => {
    const c = center(el)
    el.dispatchEvent(new PointerEvent('pointerdown', Object.assign({ bubbles: true, composed: true, pointerId: 1, pointerType: 'mouse' }, c)))
    ;['mousedown', 'pointerup', 'mouseup', 'click'].forEach(t => el.dispatchEvent(new MouseEvent(t, Object.assign({ bubbles: true, cancelable: true, composed: true, view: window }, c))))
    if (typeof el.click === 'function') { try { el.click() } catch { /* detached */ } }
  }
  const hover = (el: HTMLElement) => {
    const c = center(el)
    el.dispatchEvent(new PointerEvent('pointerover', Object.assign({ bubbles: true, composed: true, pointerId: 1, pointerType: 'mouse' }, c)))
    el.dispatchEvent(new PointerEvent('pointerenter', Object.assign({ bubbles: false, composed: true, pointerId: 1, pointerType: 'mouse' }, c)))
    el.dispatchEvent(new MouseEvent('mouseover', Object.assign({ bubbles: true, composed: true, view: window }, c)))
    el.dispatchEvent(new MouseEvent('mouseenter', Object.assign({ bubbles: false, composed: true, view: window }, c)))
    el.dispatchEvent(new MouseEvent('mousemove', Object.assign({ bubbles: true, composed: true, view: window }, c)))
  }

  if (op === 'pause') {
    let n = 0
    document.querySelectorAll('video').forEach(v => { try { v.pause(); n++ } catch { /* ignore */ } })
    return { ok: true, op, detail: 'paused ' + n }
  }

  if (op === 'open_comments') {
    const hasList = () => Array.from(document.querySelectorAll('#merge-all-comment-container [data-e2e="comment-list"], [data-e2e="comment-list"]')).some(vis)
    // 步长 500ms;tries 即总上限步数(传入处已按慢网放大),带总上限避免无限轮询
    const waitForList = async (tries: number): Promise<boolean> => {
      for (let t = 0; t < tries && !hasList(); t++) await sleep(500)
      return hasList()
    }
    if (hasList()) return { ok: true, op, detail: 'already_open' }
    // 【慢环境】评论图标本身也可能懒加载晚出,先等它出现再点(最多 ~6s),避免一上来就 comment_icon_not_found
    const findBtn = (): HTMLElement | null =>
      document.querySelector<HTMLElement>('[data-e2e="feed-comment-icon"],[data-e2e="comment-icon"],[data-e2e="video-comment"],[aria-label*="评论"]')
    let btn = findBtn()
    for (let t = 0; t < 12 && !btn; t++) { await sleep(500); btn = findBtn() }
    if (!btn) return { ok: false, op, detail: 'comment_icon_not_found' }
    // 点评论图标后【轮询】等列表出现(后台加载常 > 1.5s,固定等会误判 clicked_but_no_list)。
    // 【慢环境放大】3G 下评论懒加载更晚,首轮上限从 12×400ms(~4.8s)放大到 30×500ms(~15s 总上限)。
    click(btn)
    if (await waitForList(30)) return { ok: true, op, detail: 'opened' }
    // 再点一次(后台首点可能没触发懒加载),重试轮询也放大到 16×500ms(~8s)
    btn = findBtn() || btn
    click(btn)
    if (await waitForList(16)) return { ok: true, op, detail: 'opened_retry' }
    // 兜底:合成 'x' 快捷键(抖音全局快捷键开评论;纯 DOM keydown,后台可用,不走 CDP)
    const kev = { key: 'x', code: 'KeyX', keyCode: 88, which: 88, bubbles: true } as KeyboardEventInit
    for (const tgt of [document, document.body, window].filter(Boolean) as EventTarget[]) {
      try { tgt.dispatchEvent(new KeyboardEvent('keydown', kev)); tgt.dispatchEvent(new KeyboardEvent('keyup', kev)) } catch { /* ignore */ }
    }
    if (await waitForList(8)) return { ok: true, op, detail: 'opened_shortcut_x_dom' }
    return { ok: false, op, detail: 'clicked_but_no_list' }
  }

  if (op === 'next_video') {
    // 后台可用、不依赖 CDP:页内合成 ArrowDown 切下一个视频,并以 url 视频 id 变化确认切换成功。
    // 先记当前视频 id(modal_id 优先,否则 /video/ 后的数字),合成 ArrowDown keydown/keyup 到
    // document/body/window,多轮重试,每轮后短轮询(带上限)看视频 id 是否变化,变了即返回 switched。
    const videoId = (): string => {
      try {
        const u = new URL(location.href)
        const m = u.searchParams.get('modal_id')
        if (m) return m
        const mm = u.pathname.match(/\/video\/(\d+)/)
        if (mm && mm[1]) return mm[1]
      } catch { /* ignore */ }
      return ''
    }
    const before = videoId()
    // 短轮询等 id 变化:步长 400ms,带总上限(tries 步)避免无限轮询。
    const waitForChange = async (tries: number): Promise<string> => {
      for (let t = 0; t < tries; t++) {
        await sleep(400)
        const cur = videoId()
        if (cur && cur !== before) return cur
      }
      return ''
    }
    const sendArrowDown = () => {
      const kev = { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40, bubbles: true, cancelable: true } as KeyboardEventInit
      for (const tgt of [document, document.body, window].filter(Boolean) as EventTarget[]) {
        try { tgt.dispatchEvent(new KeyboardEvent('keydown', kev)); tgt.dispatchEvent(new KeyboardEvent('keyup', kev)) } catch { /* ignore */ }
      }
    }
    // F12 实测:合成 ArrowDown 可靠切到下一个视频;wheel(deltaY)方向不可控、会切回上一个,
    // 故只用 ArrowDown,多轮重试(后台首次合成事件可能被吞掉)。
    // (a) 首轮上限 ~6s(15×400ms)
    sendArrowDown()
    let after = await waitForChange(15)
    if (after) return { ok: true, op, detail: 'switched ' + before + '->' + after }
    // (b) 再试一次(后台首次合成事件可能被吞掉),~6s
    sendArrowDown()
    after = await waitForChange(15)
    if (after) return { ok: true, op, detail: 'switched ' + before + '->' + after }
    // (c) 末轮,~4s
    sendArrowDown()
    after = await waitForChange(10)
    if (after) return { ok: true, op, detail: 'switched ' + before + '->' + after }
    return { ok: false, op, detail: 'no_change' }
  }

  if (op === 'sort') {
    // 【慢环境】筛选触发器也可能懒加载晚出,先等它出现(最多 ~6s)再 hover
    let flt = byText('筛选')
    for (let t = 0; t < 12 && !flt; t++) { await sleep(500); flt = byText('筛选') }
    if (!flt) return { ok: false, op, detail: 'filter_trigger_not_found' }
    const want = label || '最多点赞'
    const alt = want === '最多点赞' ? '点赞最多' : want === '最新发布' ? '发布时间' : ''
    const findOpt = () => byText(want) || (alt ? byText(alt) : undefined)
    // hover 后【轮询】等下拉面板里的目标选项出现,而非固定 sleep(900) 就找——
    // 慢网下面板 JS 渲染晚,固定等会 sort_option_not_found。最多 16×500ms(~8s 总上限);
    // 每隔几次重新 hover 一次,防首个 pointerenter 在面板就绪前被吞掉。
    hover(flt)
    let opt = findOpt()
    for (let t = 0; t < 16 && !opt; t++) {
      await sleep(500)
      if (t % 4 === 3) { flt = byText('筛选') || flt; hover(flt) }
      opt = findOpt()
    }
    if (!opt) return { ok: false, op, detail: 'sort_option_not_found:' + want }
    click(opt)
    // 点完排序选项后给重排留时间;固定 sleep(1500) 在快网足够,慢网这里不强等结果就绪
    // (下一步 douyin_open_video 自带"等卡稳定+慢系数"轮询,会兜住列表重排的延迟)。
    await sleep(1500)
    return { ok: true, op, detail: 'sorted:' + want }
  }

  return { ok: false, op, detail: 'unknown_op:' + op }
}
