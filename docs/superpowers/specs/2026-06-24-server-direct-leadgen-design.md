# 服务端直连获客:扩展「网页直连」模式(免客户端) — 设计

- 日期:2026-06-24
- 状态:待用户确认
- 决策:复用 `/api/v1/browser/edge` 端点(方案 R) · 扩展新增 `connectionMode: web|client` 默认 `web` · 加生产域名白名单 `ai.devefive.com` · 接受同账号单活会话

## 1. 背景与目标

### 目标
让用户**只装浏览器扩展 + 登录网页 → 点「连接」**即可使用获客助手,**无需下载桌面客户端**。

### 非目标
- 不改变、不弱化现有桌面客户端(常驻 bridge / Native Messaging / loopback)这条路 —— **一行不动**。
- 不做"网页直连"下的细粒度采集断点续跑(列为未来增强,见 §9)。
- 不实现跨设备多活会话(沿用后端"同账号单活")。

## 2. 关键发现:这条路其实已基本建好

核查(对照真实代码)证实"网页登录→点连接→扩展直连后端"端到端**几乎全部已实现**:

| 环节 | 现状 | 代码 |
|---|---|---|
| 网页连接 UI | 已有 | `mateclaw-ui/src/views/Settings/Browser/index.vue`(BrowserPairingPanel,三态);获客页 `LeadAcquisition/index.vue` 右上「浏览器连接」按钮 |
| 网页铸令牌+交扩展 | 已有 | `browserPairingApi.mintToken()` → `Settings/Browser/pairing.ts` 用 `chrome.runtime.sendMessage(EXTENSION_ID, {type:'pair', pat, serverUrl})`;`runConnect()` 做 mint→pair→poll |
| 扩展收令牌口子 | 已有 | manifest `externally_connectable` + `onMessageExternal`(`mateclaw-extension/src/sw/index.ts:779-856`)处理 `pair`;扩展 ID 固定(manifest `key`),前端常量 `EXTENSION_ID` 已知 |
| 扩展直连后端 | 已有 | `connectDirect()`(offscreen 托管,抗 SW 挂起)→ WS `Sec-WebSocket-Protocol: mateclaw.edge.v1, bearer.<pat>` |
| 后端接受直连 | 已有 | `EdgeAuthInterceptor` 同端点**双鉴权**:bridge 走 `Authorization` header、扩展直连走 `bearer.*` subprotocol(认证层天然隔离) |
| 长任务抗断连 | 已有 | `ExtensionDouyinBrowserAdapter.withReconnect`:SESSION_DETACHED/DEADLINE_EXCEEDED 按可重试,8–10s 重连宽限 + 整步重跑 + 私信幂等守卫(clickedDmKeys)+ 单视频失败不拖垮整轮 |

**结论:这不是从零造功能,而是"打通最后一公里 + 改默认"。**

## 3. 真正的差距(为什么现在感觉"只支持客户端")

1. **白名单只允许 localhost**:`externally_connectable.matches` 与 `ALLOWED_EXTERNAL_ORIGINS` 只列了 `localhost`/`127.0.0.1` 的 18088/5173/18080。生产用 `https://ai.devefive.com` 访问网页时,网页**无权限**给扩展塞令牌 → 静默失败 → 实际只剩客户端。**这是"只支持客户端"的首要真因。**
2. **默认偏向客户端**:扩展用隐式 `preferLocalBridge`(默认 ON)优先连桌面常驻 bridge;**没有显式的"客户端/网页端"用户开关、且默认网页端**。
3. **单活会话(4409)**:后端"同账号同时只一个活跃会话",同账号同时挂桌面 bridge 和网页扩展会互相顶号。模式二选一正好规避。

## 4. 设计决策

- **端点(方案 R)**:网页直连**复用现有 `/api/v1/browser/edge`**。该端点已用 subprotocol 把扩展直连与 bridge 在认证层分开,改网页这条**不碰 bridge 分支**;获客路由按 subject 在**单一 `BrowserSessionRegistry`** 查会话,复用保证路由零改动。**不新开端点**(避免重复 handler/interceptor 与跨表路由)。
- **扩展连接模式**:新增显式 `connectionMode: 'web' | 'client'`,**默认 `web`**,取代隐式 `preferLocalBridge`。
- **单活会话**:接受同账号"客户端 XOR 网页端"二选一(同时连会 4409 顶号),由模式开关天然保证。
- **稳定性**:默认 web 依赖现有 `withReconnect` 抗 MV3 偶发重连;不新增续跑机制(MVP)。

## 5. 架构与数据流(网页直连,方案 R)

```
[用户浏览器: 登录 ai.devefive.com 网页]
        │ 1. 点「浏览器连接」
        ▼
[mateclaw-ui]  POST /api/v1/browser/pairing/mint-token  (带登录态 JWT)
        │ 2. 返回一次性明文 PAT (scope=browser:edge, 90d)
        ▼
[mateclaw-ui]  chrome.runtime.sendMessage(EXTENSION_ID, {type:'pair', pat, serverUrl:'wss://ai.devefive.com/api/v1/browser/edge'})
        │ 3. 经 externally_connectable 投递
        ▼
[扩展 SW onMessageExternal]  origin 白名单校验 → ConfigStore.setPairing({serverUrl, pat}) → connectionMode='web' → connectDirect()
        │ 4. offscreen 托管 WebSocket
        ▼
[后端 /api/v1/browser/edge]  EdgeAuthInterceptor 取 bearer.<pat> → 校验 PAT → 注册会话(subject=userId)
        │ 5. HELLO_ACK(session_id, heartbeat_interval)
        ▼
[扩展] 心跳 10s + MV3 keepalive 20s 维持;网页轮询 listSessions() 见 extensionAttached=true → 显示「已连接(绿)」

获客任务执行:
[获客后端 DouyinLeadAcquisitionExecutor]  RoutingSubjectContext.set(userId)
        → ExtensionBrowserTool.resolveSession() 按 subject 命中该用户的扩展会话
        → action.execute 下发 → 扩展 offscreen → content script → 抖音页 → action.result 回执
```

`client` 模式数据流 = 现状(常驻 bridge / loopback / Native Messaging),不变。

## 6. 扩展连接模式状态机

存储键:`connectionMode`(`chrome.storage.local`),默认 `'web'`。

引导入口(`onInstalled` / `onStartup` / `reconnectByPairing`)按 `connectionMode` 分支:

- **`web`(默认)**
  - 已配对(存在 `serverUrl`+`pat`)→ `connectDirect()`。
  - 未配对 → **空闲等待**网页 `pair` 消息(用户去网页点连接);状态显示"未连接,请在网页点连接"。
  - 收到 `pair` 消息 → 存配对 + 置 `connectionMode='web'` → `connectDirect()`(已有逻辑)。
- **`client`**
  - 走现有 `connectResidentLocal()` / Native Messaging(等同旧 `preferLocalBridge=on`)。

切换:扩展弹窗加一个 client/web 单选(默认 web);切到 `client` 时断开 direct、走 bridge,反之亦然。`unpair` 清配对但保留 `connectionMode`。

> 注:`pair` 消息隐含 web 意图,自动落 web 模式,无需用户先手动切。

## 7. 各组件改动清单(方案 R)

### 扩展 mateclaw-extension
1. `manifest.json`:`externally_connectable.matches` 增 `https://ai.devefive.com/*`;按需在 host_permissions / CSP `connect-src` 放行 `wss://ai.devefive.com`。
2. `src/sw/index.ts`:`ALLOWED_EXTERNAL_ORIGINS` 增 `https://ai.devefive.com`。
3. `src/sw/index.ts`:引入 `connectionMode`(默认 `web`),改写连接引导(`reconnectByPairing` 等)按 §6 状态机分支,取代 `preferLocalBridge` 的默认。
4. 弹窗 UI:加 client/web 单选(默认 web)。
5. 版本号 +1。

### 网页 mateclaw-ui
6. `Settings/Browser/pairing.ts`:`serverUrl` 在生产解析为 `wss://ai.devefive.com/api/v1/browser/edge`(可由当前 origin 推导或 env 注入);`EXTENSION_ID` 保持(可 env 覆盖)。
7. 连接面板:非桌面环境(`isDesktopClient=false`)默认走"网页端"连接路径(基本已是,确认默认)。
8. 文案:引导"安装扩展 → 点连接",无需下载客户端。

### 后端 mateclaw-server
9. CORS:允许 `https://ai.devefive.com`(`MATECLAW_CORS_ALLOWED_ORIGINS` 或等价配置)。
10. `/api/v1/browser/edge`:**不改**(R 复用)。
11. (可选,非 MVP)落实 `EdgeAuthInterceptor` 里 `browser:edge` scope 强校验(现为 TODO)。

## 8. 安全

- **Origin 白名单逐字符精确**:`https://ai.devefive.com`。错一个字母即静默失败(本设计要消除的坑)。manifest 与 `ALLOWED_EXTERNAL_ORIGINS` 必须一致。
- **PAT**:scope `browser:edge`,90 天,一次性明文(库存 SHA-256),由登录用户自助 mint(`mint-token` 仅校验已认证身份)。令牌经 `sendMessage` 直达扩展存 `chrome.storage.local`,**网页侧不留存**(零信任)。
- **单活会话(4409)**:同 subject 重注册顶掉旧会话并以 4409 关旧 socket。客户端/网页二选一规避;若同账号两处连,后连者赢、前者掉线(可接受)。
- **CORS**:仅放行生产网页 origin。

## 9. 稳定性与容错

- 默认 web 走 MV3 直连:offscreen 托管 socket 抗 SW 挂起;断线指数退避重连(1s→30s);重连为**全新 HELLO**(新 session_id,不触发 4409 自顶)。
- 获客层 `withReconnect` 已吸收偶发重连:8–10s 重连宽限 + 整步重跑 + 私信幂等守卫 + 天然幂等步骤(搜索/排序/开首视频)+ 单视频失败不拖垮整轮 + 虚拟线程不阻塞。
- **已知弱点(非 MVP 修)**:采集中途断连会**重采当前视频**(无细粒度断点);`clickedDmKeys` 内存态,后端重启丢失(本地 `sentDmKeys` 兜底)。未来可加采集断点 + 跨进程幂等持久化。

## 10. 错误处理与边界

- 网页点连接但扩展未装/未启用:`sendMessage` 无响应 → 网页提示"未检测到扩展,请安装/启用后重试"。
- `pair` 带非法 PAT:扩展 `connectDirect` 鉴权失败(WS 关闭非 1000)→ 回报网页"令牌无效/过期,请重试"。
- 生产 origin 未在白名单:`onMessageExternal` 拒收 → 现象=点连接无反应(部署核对项)。
- 同账号顶号(4409):被顶一方显示"已在别处连接"。

## 11. 测试点

- 扩展:`connectionMode` 默认 web;web 未配对空闲、收 `pair` 后 connectDirect;client 模式走 bridge 不受影响;白名单含生产 origin。
- 后端:edge 端点 PAT subprotocol 鉴权(已测路径回归);CORS 放行生产 origin;bridge(header)路径回归不变。
- 端到端(本地 + 生产域名各一遍):网页 mint→pair→connectDirect→listSessions 见 extensionAttached;跑一轮获客;中途人为断网验证 withReconnect 不整轮失败。
- 顶号:同账号桌面 + 网页同时连,验证 4409 行为符合预期。

## 12. 灰度与回滚

- 扩展默认 web 是行为变更:发版说明 + 让重度用户可切 client。
- 回滚:扩展把 `connectionMode` 默认改回 client(或下架新版),后端/网页改动均为加法(加 origin),回滚低风险。

## 13. 未来增强(非本次)

- 采集细粒度断点续采;跨进程私信幂等持久化。
- `browser:edge` scope 强校验。
- 扩展直连的 (session_id, msg_id) 在途请求重连后重对接(消除"丢在途 action")。
