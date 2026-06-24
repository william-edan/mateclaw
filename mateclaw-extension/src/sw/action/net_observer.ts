/**
 * 通用页内网络观察器(MAIN world) —— 抖音获客链路"基于接口判定成功 + 接口回包驱动等待"的地基。
 *
 * 一次注入、登记一组 key→URL 规则,被动包裹 window.fetch 与 XMLHttpRequest,命中即:
 *   (1) 在 documentElement 打 data-mc-net-<key>=<tsMs>          —— 轻量、跨 world(ISOLATED)免二次 MAIN 注入即可快读;
 *   (2) 解析得到响应体 status_code 时,额外打 data-mc-net-<key>-sc=<code>;
 *   (3) captureBody=true 时,把响应体存入 window.__mcNet.events 环形缓冲(供 netDrain 全量读取,如评论)。
 *
 * 它合并了原本两套同构探针(type_dm_draft.ts 的 installDmSendProbeInPage、
 * douyin_comment_network.ts 的 installDouyinCommentNetworkPageHook)的注入手法。
 *
 * 设计铁律:
 * - 纯被动:绝不 setTimeout 主动推进;轮询/超时/deadline 全由 SW 调用方控制。
 * - 自包含:executeScript({func}) 会被 toString() 序列化注入页面,页面无模块作用域,
 *   引用模块级函数会 ReferenceError 静默失败 —— 故 installNetObserverInPage 内所有 helper 必须内联。
 * - best-effort:装不上(CSP 拦截 prototype 包裹)一律静默失败,调用方 fallback 到 DOM 兜底。
 * - 加速器而非裁判:网络信号只用于"提前确认成功/就绪",从不用于"提前判失败"
 *   —— 判失败永远靠原有 DOM 信号 + deadline,故最坏情况退化到现状,零回归。
 */

export interface NetObservationRule {
  /** 业务键:'follow' | 'search' | 'sort' | 'open_video' | 'comment_list' | 'dm_send' 等 */
  key: string
  /** RegExp source(不能跨 executeScript 传 RegExp 实例,内部 new RegExp 重建) */
  urlSource: string
  /** RegExp flags,默认 'i' */
  urlFlags?: string
  /** 是否读响应体(评论这类要全量数据 / 需解析 status_code 时设 true;只要"回包到没到"则 false) */
  captureBody?: boolean
  /** captureBody 时单条响应体字符上限,默认 256K,超限只记时间戳不存体 */
  maxBodyBytes?: number
  /** 可选:响应体须包含的关键字(JSON 字段名),不含则只记时间戳 */
  bodyMatch?: string
}

/** 观察器版本守卫:接口签名(events 字段等)变更时 bump,避免页面旧副本与新注入互相破坏。 */
const NET_OBSERVER_VERSION = 2

/**
 * 安装/补登记观察器(幂等)。应在"会触发目标请求的 DOM 动作之前"注入,避免漏掉首个请求;
 * 导航后 document 重载、MAIN world 重置 → 包裹失效,需在每个会经历导航的 handler 入口重注入。
 */
export async function installNetObserver(
  api: typeof globalThis.chrome | undefined,
  tabId: number,
  rules: NetObservationRule[],
): Promise<void> {
  const chromeApi = api ?? globalThis.chrome
  if (!chromeApi?.scripting?.executeScript) return
  try {
    await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      world: 'MAIN',
      func: installNetObserverInPage,
      args: [rules, NET_OBSERVER_VERSION],
    })
  } catch {
    // best-effort —— 装不上不阻断动作,调用方退回 DOM 兜底
  }
}

export interface NetSignalOptions {
  /** 信号有效窗口(默认 30s):回包时间戳须在 [sinceTs, now-windowMs) 内 */
  windowMs?: number
  /** 若指定,要求 data-mc-net-<key>-sc 等于该值(如关注/搜索的 status_code==0) */
  statusCode?: number
}

/**
 * 轻量信号查询(ISOLATED world,读 DOM 属性,最便宜)。
 * 返回 true 表示:key 对应接口在 sinceTs 之后、windowMs 窗口内有过回包(且 status_code 命中,如要求)。
 * 用于搜索/排序/点视频/关注/私信这类"只需知道回包到没到"的早退判定。
 */
export async function netSignalSince(
  api: typeof globalThis.chrome | undefined,
  tabId: number,
  key: string,
  sinceTs: number,
  options: NetSignalOptions = {},
): Promise<boolean> {
  const chromeApi = api ?? globalThis.chrome
  if (!chromeApi?.scripting?.executeScript) return false
  const windowMs = options.windowMs ?? 30_000
  const wantStatusCode = options.statusCode ?? null
  try {
    const [r] = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      func: (k: string, since: number, win: number, wantSc: number | null) => {
        try {
          const root = document.documentElement
          const ts = Number(root.getAttribute('data-mc-net-' + k) || '0')
          if (!(ts > 0 && ts >= since && Date.now() - ts < win)) return false
          if (wantSc !== null) {
            const sc = root.getAttribute('data-mc-net-' + k + '-sc')
            if (sc === null || Number(sc) !== wantSc) return false
          }
          return true
        } catch {
          return false
        }
      },
      args: [key, sinceTs, windowMs, wantStatusCode],
    })
    return r?.result === true
  } catch {
    // tab 正在导航等 —— 视作未确认,交上层在预算内继续轮询
    return false
  }
}

export interface NetDrainResult {
  events: Array<{ key: string; url: string; status: number; statusCode: number | null; tsMs: number; body?: string; seq: number }>
  lastSeq: number
}

/**
 * 增量读取响应体(MAIN world,读 window.__mcNet.events 环形缓冲)。仅 captureBody 的节点(如评论)需要。
 * 传入上次的 sinceSeq,返回其后新增的事件 + 新的 lastSeq;调用方据 lastSeq 增量推进、避免重复。
 */
export async function netDrain(
  api: typeof globalThis.chrome | undefined,
  tabId: number,
  key: string,
  sinceSeq: number,
): Promise<NetDrainResult> {
  const chromeApi = api ?? globalThis.chrome
  if (!chromeApi?.scripting?.executeScript) return { events: [], lastSeq: sinceSeq }
  try {
    const [r] = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      world: 'MAIN',
      func: (k: string, since: number) => {
        try {
          const store = (window as unknown as { __mcNet?: { installed?: boolean; events?: Array<{ key: string; seq: number }> } }).__mcNet
          if (!store?.installed || !Array.isArray(store.events)) return { events: [], lastSeq: since }
          const evs = store.events.filter(e => e.key === k && e.seq > since)
          return { events: evs, lastSeq: evs.length ? evs[evs.length - 1].seq : since }
        } catch {
          return { events: [], lastSeq: since }
        }
      },
      args: [key, sinceSeq],
    })
    return (r?.result as NetDrainResult) ?? { events: [], lastSeq: sinceSeq }
  } catch {
    return { events: [], lastSeq: sinceSeq }
  }
}

/**
 * 诊断:读观察器当前状态(是否安装、登记了哪些 rule key、捕获了哪些 event key、关键 data-mc-net-* 属性值)。
 * 仅用于排查"接口信号为何未命中"(如排序 netReloadConfirmed=false),不参与正常判定。
 */
export async function netObserverDebug(
  api: typeof globalThis.chrome | undefined,
  tabId: number,
): Promise<Record<string, unknown> | null> {
  const chromeApi = api ?? globalThis.chrome
  if (!chromeApi?.scripting?.executeScript) return null
  try {
    const [r] = await chromeApi.scripting.executeScript({
      target: { tabId, allFrames: false },
      world: 'MAIN',
      func: () => {
        try {
          const s = (window as unknown as { __mcNet?: { installed?: boolean; rules?: Array<{ key: string }>; events?: Array<{ key: string }> } }).__mcNet
          const attr = (k: string): string | null => document.documentElement.getAttribute('data-mc-net-' + k)
          const attrs = { search: attr('search'), sort: attr('sort'), open_video: attr('open_video'), follow: attr('follow') }
          if (!s) return { installed: false, attrs }
          return {
            installed: !!s.installed,
            ruleKeys: (s.rules || []).map(x => x.key),
            eventCount: (s.events || []).length,
            eventKeys: (s.events || []).map(e => e.key),
            attrs,
          }
        } catch (e) {
          return { error: String((e as Error)?.message || e) }
        }
      },
    })
    return (r?.result as Record<string, unknown>) ?? null
  } catch {
    return null
  }
}

/**
 * 注入到页面 MAIN world 的观察器本体。**完全自包含**(所有 helper 内联),幂等,best-effort。
 * 返回 {installed, reused}:reused=true 表示同版本已在、仅补登记了新规则。
 */
function installNetObserverInPage(
  rules: Array<{ key: string; urlSource: string; urlFlags?: string; captureBody?: boolean; maxBodyBytes?: number; bodyMatch?: string }>,
  ver: number,
): { installed: boolean; reused: boolean } {
  const STORE_KEY = '__mcNet'
  const w = window as unknown as Record<string, unknown>

  // —— 内联 helper(自包含铁律:不得引用任何模块级函数) ——
  const hostOk = (u: string): boolean => {
    try {
      const h = new URL(u, location.href).hostname.toLowerCase()
      return h.indexOf('douyin.com') >= 0 || h.indexOf('iesdouyin.com') >= 0 || h.indexOf('amemv.com') >= 0
    } catch {
      return false
    }
  }
  const parseStatusCode = (body: string): number | null => {
    try {
      const j = JSON.parse(body) as Record<string, unknown>
      const raw = (j.status_code ?? j.statusCode ?? j.code ?? j.status) as unknown
      return typeof raw === 'number' ? raw : null
    } catch {
      return null
    }
  }
  type CompiledRule = { key: string; re: RegExp; captureBody: boolean; maxBytes: number; bodyMatch?: string }
  const compile = (raw: typeof rules): CompiledRule[] =>
    raw.map(r => ({
      key: r.key,
      re: new RegExp(r.urlSource, r.urlFlags || 'i'),
      captureBody: !!r.captureBody,
      maxBytes: r.maxBodyBytes || 262144,
      bodyMatch: r.bodyMatch,
    }))

  type Store = {
    ver: number
    installed: boolean
    rules: CompiledRule[]
    events: Array<{ key: string; url: string; status: number; statusCode: number | null; tsMs: number; body?: string; seq: number }>
    seq: number
    cap: number
  }

  // —— 幂等:同版本已在,只补登记新 key,不重复包裹 fetch/XHR(防叠多层 wrapper) ——
  const existing = w[STORE_KEY] as Store | undefined
  if (existing && existing.installed && existing.ver === ver) {
    for (const r of compile(rules)) {
      if (!existing.rules.some(x => x.key === r.key)) existing.rules.push(r)
    }
    return { installed: true, reused: true }
  }

  const store: Store = {
    ver,
    installed: true,
    rules: compile(rules),
    events: [],
    seq: 1,
    cap: 200,
  }
  w[STORE_KEY] = store

  const matchRule = (u: string): CompiledRule | undefined =>
    hostOk(u) ? store.rules.find(r => r.re.test(u)) : undefined

  // 唯一写出口:同时双写 events 环形缓冲 + DOM 属性
  const record = (rule: CompiledRule, url: string, status: number, body?: string): void => {
    const tsMs = Date.now()
    const statusCode = typeof body === 'string' && body ? parseStatusCode(body) : null
    store.events.push({ key: rule.key, url, status, statusCode, tsMs, body: rule.captureBody ? body : undefined, seq: store.seq++ })
    if (store.events.length > store.cap) store.events.splice(0, store.events.length - store.cap)
    try {
      const root = document.documentElement
      root.setAttribute('data-mc-net-' + rule.key, String(tsMs))
      if (statusCode !== null) root.setAttribute('data-mc-net-' + rule.key + '-sc', String(statusCode))
    } catch {
      /* ignore */
    }
  }

  // —— fetch 包裹(captureBody 才 clone().text(),否则只记时间戳) ——
  try {
    const of = w.fetch as (typeof fetch) & { __mcNetWrapped?: boolean }
    if (typeof of === 'function' && !of.__mcNetWrapped) {
      const wrapped = async function (this: unknown, input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
        const resp = await of.apply(this, [input, init] as Parameters<typeof fetch>)
        try {
          const url = (resp && resp.url) || (typeof input === 'string' ? input : (input as Request)?.url) || ''
          const rule = matchRule(String(url))
          if (rule) {
            if (rule.captureBody) {
              resp.clone().text().then(b => {
                try {
                  if (b && b.length <= rule.maxBytes && (!rule.bodyMatch || b.indexOf(rule.bodyMatch) >= 0)) record(rule, String(url), resp.status, b)
                  else record(rule, String(url), resp.status)
                } catch {
                  record(rule, String(url), resp.status)
                }
              }).catch(() => {
                try { record(rule, String(url), resp.status) } catch { /* ignore */ }
              })
            } else {
              record(rule, String(url), resp.status)
            }
          }
        } catch {
          /* ignore */
        }
        return resp
      } as (typeof fetch) & { __mcNetWrapped?: boolean }
      wrapped.__mcNetWrapped = true
      w.fetch = wrapped
    }
  } catch {
    /* ignore */
  }

  // —— XHR 包裹(open 记 url,loadend 取 status/body) ——
  try {
    const proto = XMLHttpRequest.prototype as XMLHttpRequest & { open: ((...a: unknown[]) => void) & { __mcNetWrapped?: boolean }; send: (...a: unknown[]) => void }
    if (typeof proto.open === 'function' && !proto.open.__mcNetWrapped) {
      const originalOpen = proto.open
      proto.open = function (this: XMLHttpRequest & { __mcNetUrl?: string }, ...args: unknown[]): void {
        try { this.__mcNetUrl = typeof args[1] === 'string' ? args[1] : String(args[1]) } catch { /* ignore */ }
        return originalOpen.apply(this, args as never)
      } as typeof proto.open
      ;(proto.open as { __mcNetWrapped?: boolean }).__mcNetWrapped = true
      const originalSend = proto.send
      proto.send = function (this: XMLHttpRequest & { __mcNetUrl?: string }, ...args: unknown[]): void {
        try {
          this.addEventListener('loadend', () => {
            try {
              const url = this.responseURL || this.__mcNetUrl || ''
              const rule = matchRule(url)
              if (!rule) return
              let body = ''
              if (rule.captureBody) {
                try { if (this.responseType === '' || this.responseType === 'text') body = String(this.responseText || '') } catch { /* ignore */ }
              }
              record(rule, url, this.status, rule.captureBody ? body : undefined)
            } catch {
              /* ignore */
            }
          })
        } catch {
          /* ignore */
        }
        return originalSend.apply(this, args as never)
      } as typeof proto.send
    }
  } catch {
    /* ignore */
  }

  return { installed: true, reused: false }
}
