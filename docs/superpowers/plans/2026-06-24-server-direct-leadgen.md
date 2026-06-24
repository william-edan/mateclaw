# 服务端直连获客:扩展「网页直连」模式(免客户端) — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户只装浏览器扩展 + 登录 `ai.devefive.com` 点「连接」即可用获客助手,免桌面客户端;桌面客户端路径保持不变。

**Architecture:** 复用现有 `/api/v1/browser/edge`(方案 R)。网页直连这条链路代码里已基本建好(网页连接面板、mint→pair→交令牌、扩展 `connectDirect`/offscreen、后端 edge 双鉴权、获客层 `withReconnect`)。本计划只补「最后一公里」:① 把生产域名加进扩展的 `externally_connectable` 白名单(当前只有 localhost,这是"只支持客户端"的真因);② 给扩展加显式 `connectionMode: web|client`(默认 `web`),未配对时 web 模式等待网页 pair、client 模式走现有桌面常驻 bridge;③ 生产配 CORS 环境变量。

**Tech Stack:** 扩展 = TypeScript + Vue3(vite build / vitest)。网页 = Vue3。后端 = Spring Boot。

**核查结论(已对照真实代码,故大量"原本以为要改"其实无需改):**
- 网页 `deriveWsUrl()` 已从 `location.origin` 推导 → 生产部署在 `ai.devefive.com` 时自动得到 `wss://ai.devefive.com/api/v1/browser/edge`。**网页侧零改动。**
- 非桌面环境网页**已经 100%** 走 `runConnect()`(mint→pair→直连)。**网页侧零改动。**
- 后端 edge WS 已 `setAllowedOriginPatterns("chrome-extension://*")` → 扩展直连 Origin 已放行;HTTP CORS 默认 `*`。**后端零代码改动**,生产仅设环境变量。
- 扩展 manifest CSP 的 `connect-src` 已含 `wss://*` → 连生产 wss 无需改 CSP。

---

## 文件结构(创建/修改)

| 文件 | 动作 | 职责 |
|---|---|---|
| `mateclaw-extension/src/sw/connection-mode.ts` | 创建 | 纯函数:读写 `connectionMode`(默认 `web`),可单测 |
| `mateclaw-extension/src/sw/connection-mode.spec.ts` | 创建 | connection-mode 单测 |
| `mateclaw-extension/manifest.json` | 修改 | `externally_connectable.matches` 加生产 origin;version +1 |
| `mateclaw-extension/src/sw/index.ts` | 修改 | `ALLOWED_EXTERNAL_ORIGINS` 加生产 origin;`reconnectByPairing` 未配对分支按 mode 分流;内部 onMessage 加 `bridge.setMode`、`bridge.status` 回带 mode |
| `mateclaw-extension/src/sidepanel/App.vue` | 修改 | 加 客户端/网页端 单选开关 |
| (部署) `MATECLAW_CORS_ALLOWED_ORIGINS` | 配置 | 生产放行 `https://ai.devefive.com` |

---

## Task 1: connection-mode 纯函数模块(TDD)

**Files:**
- Create: `mateclaw-extension/src/sw/connection-mode.ts`
- Test: `mateclaw-extension/src/sw/connection-mode.spec.ts`

- [ ] **Step 1: 写失败测试**

`mateclaw-extension/src/sw/connection-mode.spec.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { getConnectionMode, setConnectionMode, CONNECTION_MODE_KEY } from './connection-mode'

function fakeStorage(initial: Record<string, unknown> = {}) {
  const store: Record<string, unknown> = { ...initial }
  return {
    store,
    get: async (k: string | string[]) => {
      const keys = Array.isArray(k) ? k : [k]
      const out: Record<string, unknown> = {}
      for (const key of keys) if (key in store) out[key] = store[key]
      return out
    },
    set: async (items: Record<string, unknown>) => {
      Object.assign(store, items)
    },
  }
}

describe('connection-mode', () => {
  it('未设置时默认 web', async () => {
    expect(await getConnectionMode(fakeStorage())).toBe('web')
  })
  it('存了 client 就返回 client', async () => {
    expect(await getConnectionMode(fakeStorage({ [CONNECTION_MODE_KEY]: 'client' }))).toBe('client')
  })
  it('非法值回退 web', async () => {
    expect(await getConnectionMode(fakeStorage({ [CONNECTION_MODE_KEY]: 'bogus' }))).toBe('web')
  })
  it('读 storage 抛错也回退 web', async () => {
    const throwing = {
      get: async () => {
        throw new Error('boom')
      },
      set: async () => {},
    }
    expect(await getConnectionMode(throwing)).toBe('web')
  })
  it('setConnectionMode 落库', async () => {
    const s = fakeStorage()
    await setConnectionMode(s, 'client')
    expect(s.store[CONNECTION_MODE_KEY]).toBe('client')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd mateclaw-extension && pnpm vitest run src/sw/connection-mode.spec.ts`
Expected: FAIL —— "Cannot find module './connection-mode'"。

- [ ] **Step 3: 写实现**

`mateclaw-extension/src/sw/connection-mode.ts`:

```ts
/**
 * 扩展连接模式(server-direct 设计)。
 *   - web   (默认):只装扩展 + 网页登录点连接 → 直连后端;未配对时空闲等网页 pair。
 *   - client:走桌面客户端常驻 bridge / native(现状路径不变)。
 *
 * 纯函数 + 注入 storage,便于单测;不在模块加载期触碰全局 chrome。
 */
export type ConnectionMode = 'web' | 'client'

export const CONNECTION_MODE_KEY = 'connectionMode'

const DEFAULT_MODE: ConnectionMode = 'web'

/** chrome.storage.local 的最小子集(MV3 下 get/set 返回 Promise)。 */
export interface StorageLike {
  get(keys: string | string[]): Promise<Record<string, unknown>>
  set(items: Record<string, unknown>): Promise<void>
}

/** 读取连接模式;未设置/非法值/读失败一律回退默认 web。 */
export async function getConnectionMode(storage: StorageLike): Promise<ConnectionMode> {
  try {
    const got = await storage.get(CONNECTION_MODE_KEY)
    return got[CONNECTION_MODE_KEY] === 'client' ? 'client' : DEFAULT_MODE
  } catch {
    return DEFAULT_MODE
  }
}

/** 写入连接模式。 */
export async function setConnectionMode(storage: StorageLike, mode: ConnectionMode): Promise<void> {
  await storage.set({ [CONNECTION_MODE_KEY]: mode })
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd mateclaw-extension && pnpm vitest run src/sw/connection-mode.spec.ts`
Expected: PASS(5 passed)。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-extension/src/sw/connection-mode.ts mateclaw-extension/src/sw/connection-mode.spec.ts
git commit -m "feat(扩展): connection-mode 模块(默认 web),为网页直连模式开关铺路"
```

---

## Task 2: 生产域名加进扩展白名单(真正的"只支持客户端"修复)

**Files:**
- Modify: `mateclaw-extension/manifest.json`(`externally_connectable.matches` + `version`)
- Modify: `mateclaw-extension/src/sw/index.ts:142-149`(`ALLOWED_EXTERNAL_ORIGINS`)

- [ ] **Step 1: manifest 加生产 origin + 升版本**

`mateclaw-extension/manifest.json` —— 把 `externally_connectable.matches` 数组末尾加一项,并把 `version` `0.1.38` → `0.1.39`。

改后 `externally_connectable`:

```json
  "externally_connectable": {
    "matches": [
      "http://localhost:18088/*",
      "http://localhost:5173/*",
      "http://localhost:18080/*",
      "http://127.0.0.1:18088/*",
      "http://127.0.0.1:5173/*",
      "http://127.0.0.1:18080/*",
      "https://ai.devefive.com/*"
    ]
  },
```

并改顶部:`"version": "0.1.39",`

- [ ] **Step 2: SW 白名单加生产 origin**

`mateclaw-extension/src/sw/index.ts:142-149` —— `ALLOWED_EXTERNAL_ORIGINS` 末尾加一行。改后:

```ts
const ALLOWED_EXTERNAL_ORIGINS = new Set<string>([
  'http://localhost:18088',
  'http://localhost:5173',
  'http://localhost:18080',
  'http://127.0.0.1:18088',
  'http://127.0.0.1:5173',
  'http://127.0.0.1:18080',
  'https://ai.devefive.com',
])
```

> 注:`externally_connectable.matches` 用 `/*` 通配路径;`ALLOWED_EXTERNAL_ORIGINS` 是**纯 origin**(无路径)。两处必须逐字符一致(`https://ai.devefive.com`),错一个字母网页就塞不进令牌、静默连不上。

- [ ] **Step 3: 构建确认无误**

Run: `cd mateclaw-extension && pnpm build`
Expected: 构建成功;`dist/manifest.json` 含 `https://ai.devefive.com/*`。

校验:`grep -c "ai.devefive.com" mateclaw-extension/dist/manifest.json` → 期望 ≥ 1。

- [ ] **Step 4: 提交**

```bash
git add mateclaw-extension/manifest.json mateclaw-extension/src/sw/index.ts
git commit -m "feat(扩展): externally_connectable 与 onMessageExternal 白名单加生产域名 ai.devefive.com"
```

---

## Task 3: `reconnectByPairing` 未配对分支按 connectionMode 分流(默认 web)

**Files:**
- Modify: `mateclaw-extension/src/sw/index.ts`(import + `reconnectByPairing` 末段)

- [ ] **Step 1: 引入 connection-mode**

在 `mateclaw-extension/src/sw/index.ts` 顶部 import 区加(与同目录其它 import 并列):

```ts
import { getConnectionMode } from './connection-mode'
import type { ConnectionMode } from './connection-mode'
```

- [ ] **Step 2: 改 `reconnectByPairing` 的未配对分支**

`mateclaw-extension/src/sw/index.ts:405-414`。**已配对分支(serverUrl+pat → connectDirect/offscreen)完全不动**,只改"未配对"那段。

把现有:

```ts
  // 跨组契约4: prefer the resident bridge loopback (SW-direct LocalBridgeClient)
  // unless the flag is explicitly disabled. No offscreen requirement — the
  // resident transport is now held directly by the SW (see connectResidentLocal),
  // so it works on any runtime; flag off ⇒ fall through to native "装好即连",
  // preserving the legacy behaviour exactly.
  if (await preferLocalBridge()) {
    connectResidentLocal()
    return
  }
  connectNative()
}
```

替换为:

```ts
  // server-direct:未配对时按连接模式分流(默认 web)。
  //   web(默认)  — 不主动连本地 bridge,空闲等待网页「点连接」推送 pair(externally_connectable)
  //                后,配对分支会走 connectDirect。
  //   client      — 走桌面常驻 bridge / native"装好即连"(现状不变;跨组契约4 的 preferLocalBridge
  //                作为 client 模式内部的 resident vs native 选择,保留)。
  const mode: ConnectionMode = await getConnectionMode(chrome.storage.local)
  if (mode === 'client') {
    if (await preferLocalBridge()) {
      connectResidentLocal()
      return
    }
    connectNative()
    return
  }
  // mode === 'web':未配对 → 空闲,等待网页 pair。
}
```

- [ ] **Step 3: 类型检查 + 构建**

Run: `cd mateclaw-extension && pnpm build`
Expected: 构建成功,无 TS 报错(`getConnectionMode`/`ConnectionMode` 解析正常)。

- [ ] **Step 4: 提交**

```bash
git add mateclaw-extension/src/sw/index.ts
git commit -m "feat(扩展): reconnectByPairing 未配对分支按 connectionMode 分流,默认 web 不主动连本地 bridge"
```

> ⚠️ **桌面零配置代价(已在 spec 知会)**:默认改为 web 后,**桌面用户**若什么都不做,扩展不再自动连常驻 bridge;需在扩展弹窗把模式切到「客户端」一次(Task 4 提供该开关)。客户端**代码路径**未变,变的是默认值——符合"默认网页端"的指示。

---

## Task 4: `bridge.setMode` 消息 + sidepanel 客户端/网页端开关

**Files:**
- Modify: `mateclaw-extension/src/sw/index.ts`(内部 `onMessage` 加 `bridge.setMode`;`bridge.status` 回带 `mode`)
- Modify: `mateclaw-extension/src/sidepanel/App.vue`(加单选开关)

- [ ] **Step 1: 内部 onMessage 处理器扩展**

先把 Task 3 加的 import 扩成同时引入 `setConnectionMode`:

```ts
import { getConnectionMode, setConnectionMode } from './connection-mode'
```

`mateclaw-extension/src/sw/index.ts:876` —— 把 `r` 的类型断言加上 `mode`:

```ts
    const r = req as { kind?: string; message?: unknown; serverUrl?: string; pat?: string; deviceName?: string; mode?: string }
```

`mateclaw-extension/src/sw/index.ts:904-917` —— 把 `bridge.status` 改为同时回带当前 mode,并在其后新增 `bridge.setMode`:

```ts
      case 'bridge.status':
        Promise.all([configStore.getConfig(), getConnectionMode(chrome.storage.local)])
          .then(([cfg, mode]) =>
            sendResponse({
              connected: isConnected(),
              unpaired: isNativeUnpaired(),
              serverUrl: cfg.serverUrl ?? null,
              deviceName: cfg.deviceName ?? null,
              deviceId: cfg.deviceId,
              mode,
            }),
          )
          .catch(e => sendResponse({ connected: false, error: String(e) }))
        return true
      case 'bridge.setMode': {
        const mode: ConnectionMode = r.mode === 'client' ? 'client' : 'web'
        setConnectionMode(chrome.storage.local, mode)
          // 切模式后立即按新模式重连(web 未配对=空闲;client=连 bridge/native)。
          .then(() => reconnectByPairing())
          .then(() => sendResponse({ ok: true, mode }))
          .catch(e => sendResponse({ ok: false, error: String(e) }))
        return true
      }
```

- [ ] **Step 2: sidepanel 加开关**

`mateclaw-extension/src/sidepanel/App.vue` —— 在 `<template>` 的 status 行下方(`</div>` 后、Server URL 字段前)插入模式单选:

```html
      <div class="row">
        <span class="label">Mode</span>
        <span class="seg">
          <button
            class="seg-btn"
            :class="{ active: connMode === 'web' }"
            data-test="mode-web"
            @click="setMode('web')"
          >网页端</button>
          <button
            class="seg-btn"
            :class="{ active: connMode === 'client' }"
            data-test="mode-client"
            @click="setMode('client')"
          >客户端</button>
        </span>
      </div>
```

`<script setup>` 内,在 `const connState = ref<ConnState>('closed')` 之后加:

```ts
const connMode = ref<'web' | 'client'>('web')

async function setMode(m: 'web' | 'client') {
  connMode.value = m
  await chrome.runtime.sendMessage({ kind: 'bridge.setMode', mode: m })
  void refreshStatus()
}
```

并在 `refreshStatus()` 里,把读到的 `mode` 同步到 `connMode`(在已有的 `if (typeof res?.serverUrl === 'string' ...)` 之后加):

```ts
    const mode = (res as { mode?: string } | undefined)?.mode
    if (mode === 'web' || mode === 'client') {
      connMode.value = mode
    }
```

`<style scoped>` 末尾加:

```css
.seg {
  display: inline-flex;
  gap: 4px;
}
.seg-btn {
  padding: 2px 10px;
  border: 1px solid #ccc;
  border-radius: 999px;
  background: #fff;
  font: inherit;
  cursor: pointer;
}
.seg-btn.active {
  background: #2e7d32;
  border-color: #2e7d32;
  color: #fff;
}
```

- [ ] **Step 3: 构建确认无误**

Run: `cd mateclaw-extension && pnpm build`
Expected: 构建成功,sidepanel 编译通过。

- [ ] **Step 4: 提交**

```bash
git add mateclaw-extension/src/sw/index.ts mateclaw-extension/src/sidepanel/App.vue
git commit -m "feat(扩展): sidepanel 加 客户端/网页端 开关 + bridge.setMode 消息"
```

---

## Task 5: 后端生产 CORS 放行(部署配置,无代码改动)

**Files:** 无代码改动。后端 edge WS 已 `setAllowedOriginPatterns("chrome-extension://*")`(扩展 Origin 已放行);HTTP CORS 由 `MATECLAW_CORS_ALLOWED_ORIGINS` 控制(默认 `*`)。

- [ ] **Step 1: 生产环境设环境变量**

生产部署(docker-compose / 启动脚本)设置:

```
MATECLAW_CORS_ALLOWED_ORIGINS=https://ai.devefive.com
```

说明:网页在 `https://ai.devefive.com` 调 `/api/v1/browser/pairing/mint-token` 等接口受 HTTP CORS 约束,必须放行该 origin。开发环境默认 `*` 已可用,无需改。

- [ ] **Step 2: 验证(启动后)**

后端启动日志若仍是默认 `*` 会打印 `[Security] CORS allows all origins...`;生产设了该变量后该告警消失。
浏览器 DevTools Network 里,网页对 `/api/v1/browser/pairing/mint-token` 的预检/请求**无 CORS 报错**。

(此任务无 git 提交——属部署配置。)

---

## Task 6: 端到端真机验证

**前置:** 后端必须在跑(本地 H2 或 MySQL 均可);网页(`pnpm dev` 的 5173 或生产 `ai.devefive.com`);Chrome 加载扩展(`mateclaw-extension/dist` 解包加载,或装已发布版)。

- [ ] **Step 1: 加载新扩展并确认默认模式**

`cd mateclaw-extension && pnpm build` → Chrome `chrome://extensions` 解包加载 `dist`。打开扩展 sidepanel,确认 Mode 默认高亮「网页端」、Status 为 disconnected(未配对,web 模式空闲——符合预期)。

- [ ] **Step 2: 网页一键连接**

登录网页(本地 `http://localhost:5173`,或生产 `https://ai.devefive.com`)→ 进「获客」页或 设置→浏览器 → 点「连接」。
预期:网页 mint→pair→poll 后显示「已连接(绿)」;扩展 sidepanel Status 变 connected。
后端日志可见 `/api/v1/browser/edge` 收到 PAT subprotocol 鉴权 + 会话注册(subject=userId)。

- [ ] **Step 3: 跑一轮获客验证链路 + 容错**

发起一个抖音获客任务,确认动作下发/回执正常。中途人为断网几秒再恢复:确认 `withReconnect` 重连后任务继续(不整轮失败),而非卡死。

- [ ] **Step 4: 切客户端模式回归(若有桌面环境)**

扩展 sidepanel 点「客户端」→ 确认走常驻 bridge / native(桌面场景),与改动前一致。再切回「网页端」。

- [ ] **Step 5: 生产域名白名单负向验证**

故意在网页用一个**未白名单**的 origin(或确认拼写)→ 点连接应"无反应/未检测到扩展";加白名单后恢复。确认白名单逐字符正确(`ai.devefive.com`)。

---

## 部署/灰度备注

- 扩展默认 web 是行为变更:发版说明需提示桌面用户切「客户端」。
- 回滚:扩展 `connection-mode.ts` 的 `DEFAULT_MODE` 改回 `'client'`(或下架新版);后端/网页改动均为加法,低风险。
- `application-mysql.yml` 里远程库明文密码:提交前改走 `DB_PASSWORD` 环境变量,勿入库(与本特性无关,但同批注意)。
