/**
 * 慢机自适应「激活兜底」—— 把目标 tab 临时激活到前台,解除 Chrome 后台节流。
 *
 * Chrome 对不可见(后台/最小化)tab 做 timer + 渲染节流;抖音 SPA 的关注/私信按钮、私信
 * 浮层都靠异步渲染 mount。后台节流 + 慢 CPU 双重拖累下,这些元素迟迟不出现,DOM 操作扑空
 * (FOLLOW_BUTTON_NOT_FOUND / DM_INPUT_NOT_FOUND)。visibility-keepalive 只骗得了抖音自己
 * 的 JS,骗不了浏览器引擎层的节流;唯一可靠解除节流的办法是让 tab 真正可见。
 *
 * 策略:后台先试(静默),扑空才激活——快机/前台元素本就在,不会触发激活,行为不变;慢机
 * 激活后全速渲染、元素出来再操作。best-effort:激活失败绝不可让动作失败。
 */
export async function activateTabForRender(
  api: typeof globalThis.chrome | undefined,
  tabId: number,
): Promise<void> {
  const chromeApi = api ?? globalThis.chrome
  try {
    const tab = await chromeApi.tabs.update(tabId, { active: true })
    const windowId = tab?.windowId
    if (typeof windowId === 'number') {
      try {
        await chromeApi.windows.update(windowId, { focused: true })
      } catch {
        // best-effort —— 窗口聚焦失败不影响 tab 已激活
      }
    }
  } catch {
    // best-effort —— 激活失败不阻断后续 DOM 尝试
  }
}

/** 页面里是否存在 selector 命中的元素。executeScript 在后台 tab 也能跑,不受可见性限制。 */
async function selectorPresent(
  chromeApi: typeof globalThis.chrome,
  tabId: number,
  selector: string,
): Promise<boolean> {
  try {
    const [r] = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: (sel: string) => !!document.querySelector(sel),
      args: [selector],
    })
    return r?.result === true
  } catch {
    // tab 可能正在导航 —— 视作未渲染,交给上层轮询重试
    return false
  }
}

/**
 * 确保 selector 命中的元素已渲染:已在直接返回(静默不激活);不在则激活 tab 解除节流、
 * 轮询等待至多 timeoutMs。返回最终是否渲染出来。
 */
export async function ensureRendered(
  api: typeof globalThis.chrome | undefined,
  tabId: number,
  selector: string,
  timeoutMs: number,
): Promise<boolean> {
  const chromeApi = api ?? globalThis.chrome
  if (!chromeApi?.scripting?.executeScript) return false
  if (await selectorPresent(chromeApi, tabId, selector)) return true
  // 没渲染 → 慢机/后台节流,激活 tab 全速渲染后轮询等待。
  await activateTabForRender(chromeApi, tabId)
  const deadline = Date.now() + Math.max(0, timeoutMs)
  while (Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 600))
    if (await selectorPresent(chromeApi, tabId, selector)) return true
  }
  return false
}
