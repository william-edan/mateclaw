/**
 * 后台保活 —— 让页面"以为自己一直可见"。
 *
 * Chrome 会暂停不可见(切到其他 tab / 窗口被遮挡或最小化)tab 的渲染与定时器,
 * 抖音自身也监听 visibilitychange,一旦判定 hidden 就暂停懒加载/视频/评论续拉。
 * 表现:后台时第一个视频卡的时长来不及渲染(被当成图文跳过→点到第二个)、
 * 评论列表新内容不渲染、续拉不触发(滚不动、只有第一页)。
 *
 * 解法:在 MAIN world 注入脚本,覆盖 document.visibilityState/hidden/hasFocus,
 * 并在捕获阶段吞掉 visibilitychange,让抖音的页面逻辑始终以为自己可见、不暂停。
 *
 * 注入时机很关键:必须在用户切到后台【之前】就位。获客每一步导航后(搜索结果页 /
 * 视频页)document 会重载、override 随之失效,故在每个会经历导航的 douyin handler
 * (douyin_search / douyin_ui / douyin_open_video)开头都幂等重注入一次。采集滚动
 * 与打开评论是同一个 document,override 持续有效,无需单独注入。
 *
 * 幂等:页面侧用 window.__mcVisOverride 标记,已注入则跳过。best-effort —
 * 注入失败绝不可让动作失败。
 */
export async function ensureVisibilityOverride(
  api: typeof globalThis.chrome | undefined,
  tabId: number,
): Promise<void> {
  const chromeApi = api ?? globalThis.chrome
  if (!chromeApi?.scripting?.executeScript) return
  try {
    await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      world: 'MAIN',
      func: installVisibilityOverrideInPage,
    })
  } catch {
    // best-effort —— 后台保活注入失败不影响后续 DOM 动作本身
  }
}

/** 注入到页面 MAIN world。纯 DOM、幂等。覆盖可见性 API 并拦截 visibilitychange。 */
function installVisibilityOverrideInPage(): string {
  const w = window as unknown as { __mcVisOverride?: boolean }
  if (w.__mcVisOverride) return 'already'
  w.__mcVisOverride = true

  const define = (obj: object, prop: string, value: unknown): void => {
    try {
      Object.defineProperty(obj, prop, { configurable: true, get: () => value })
    } catch {
      /* 某些属性不可重定义,忽略 */
    }
  }
  define(document, 'visibilityState', 'visible')
  define(document, 'hidden', false)
  define(document, 'webkitVisibilityState', 'visible')
  define(document, 'webkitHidden', false)
  try {
    (document as unknown as { hasFocus: () => boolean }).hasFocus = () => true
  } catch {
    /* ignore */
  }

  // 捕获阶段吞掉可见性变更事件,阻止抖音据此暂停加载/续拉。
  // 只拦 visibilitychange 系列,不碰 blur/focus(避免影响输入框等正常焦点逻辑)。
  const swallow = (e: Event): void => {
    try { e.stopImmediatePropagation() } catch { /* ignore */ }
  }
  for (const ev of ['visibilitychange', 'webkitvisibilitychange', 'mozvisibilitychange']) {
    try { document.addEventListener(ev, swallow, true) } catch { /* ignore */ }
    try { window.addEventListener(ev, swallow, true) } catch { /* ignore */ }
  }
  return 'installed'
}
