# 设计：DashScope / DeepSeek「默认版」云端模型（平台预置 key，开箱即用）

- 日期：2026-06-17
- 范围：设置 → 模型管理 → 云端模型
- 状态：已与用户确认设计，待评审 spec

## 1. 背景与目标

当前「模型管理 / 云端模型」里内置了 `dashscope`、`dashscope-compat`、`deepseek` 等 provider。
平台默认 key 已配置在 `application.yml`：

```yaml
mateclaw:
  llm:
    default-provider-keys:
      dashscope: ${MATECLAW_DEFAULT_DASHSCOPE_API_KEY:sk-...}
      deepseek:  ${MATECLAW_DEFAULT_DEEPSEEK_API_KEY:sk-...}
```

现有代码 `ModelProviderService.applyRegistrationDefaultProviderKey()` 把这把平台 key
**直接套在原版 `dashscope` / `deepseek` 上**，且只在 **新建工作区 seed** 时触发；
默认工作区里这俩 provider 的 `api_key` 为空。这造成「平台 key」与「用户自带 key」混在同一条目里。

**目标**：把两者拆开。

1. 新增三个内置「默认版」provider，预置配置文件里的平台 key、开箱启用，key 在 UI 上隐藏只读、不可删除。
2. 原版 `dashscope` / `dashscope-compat` / `deepseek` 改为「自带 key」：空 key、默认不启用，用户通过「添加 Provider」抽屉添加后填自己的 key。

### 已确认的关键决策

- 新增独立「默认版」provider；原版清空 key 留给用户。
- 兼容模式也做默认版（部分带点号版本 qwen 模型只能走 compatible-mode 端点）。
- 默认版的平台 key 隐藏、只读、不可删除。
- 实现选型 **A**：用代码里的「受管 provider id 集合」判定 + DTO 计算字段，不新增数据库列。

## 2. 受影响的现有结构

- 实体：`mate_model_provider`（`ModelProviderEntity`）、`mate_model_config`（`ModelConfigEntity`，含 `workspace_id`、`is_default`、`builtin`、`model_type`）。
- 服务：`ModelProviderService`（`defaultProviderKey()`、`applyRegistrationDefaultProviderKey()`、`copyProviderForWorkspace()`、`updateProviderConfig()`、`toProviderInfo()`）。
- DTO：`ProviderInfoDTO`。
- 配置：`DefaultProviderKeyProperties`（`prefix = mateclaw.llm.default-provider-keys`，字段 `dashscope` / `deepseek`）。
- 数据交付：Flyway（`db/migration/{h2,mysql}/`，最新 **V144**）+ 全新库种子（`db/data-zh.sql`、`data-en.sql`、`data-mysql-zh.sql`、`data-mysql-en.sql`）。种子仅在全新库首次启动跑（`DatabaseBootstrapRunner`，Flyway 之后）。
- 前端：`mateclaw-ui/src/views/Settings/Models/`（`useProviders.ts`、`composables/useProviderForm.ts`、`ProviderCard.vue`、`AddProviderDrawer.vue`）、`src/api/index.ts`。

## 3. 详细设计

### 3.1 新增三个「默认版」provider

| provider_id | name(zh) | name(en) | chat_model | base_url |
|---|---|---|---|---|
| `dashscope-default` | DashScope 默认 | DashScope (Default) | `DashScopeChatModel` | 空（native SDK） |
| `dashscope-compat-default` | DashScope 兼容模式 默认 | DashScope Compatible (Default) | `OpenAIChatModel` | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `deepseek-default` | DeepSeek 默认 | DeepSeek (Default) | `OpenAIChatModel` | `https://api.deepseek.com` |

provider 行属性：

- `is_custom = FALSE`（内置 → 不可删除，沿用现有 `deleteCustomProvider` 守卫）
- `is_local = FALSE`
- `require_api_key = TRUE`
- `freeze_url = TRUE`（base_url 锁定）
- `support_model_discovery = FALSE`（锁定模型集，防止用平台共享 key 拉任意模型）
- `support_connection_check = TRUE`（允许用户做连接测试）
- `enabled = TRUE`（开箱即用，出现在主网格）
- `api_key`：种子里留空；由「平台 key 注入」逻辑填入（见 3.2），保证 env 覆盖生效。

### 3.2 平台 key 注入（单一来源）

后端集中定义受管集合与映射（建议放在 `ModelProviderService` 或一个小常量类）：

```
MANAGED_DEFAULT_PROVIDER_IDS = { "dashscope-default", "dashscope-compat-default", "deepseek-default" }

defaultProviderKey(id):
  dashscope-default        -> properties.getDashscope()
  dashscope-compat-default -> properties.getDashscope()   // 与 native 共用同一把 key
  deepseek-default         -> properties.getDeepseek()
  其它                      -> null
```

- 改 `defaultProviderKey()` 的 switch：**移除** `dashscope`/`deepseek`，**改为**上面三个新 id。
- **新建工作区**：`copyProviderForWorkspace() → applyRegistrationDefaultProviderKey()` 已存在；id 改了即自动覆盖新默认版。
- **默认工作区**（当前空档）：新增启动组件，监听 `ApplicationReadyEvent`（或现有就绪钩子），对默认工作区里 `MANAGED_DEFAULT_PROVIDER_IDS` 的每个 provider：当 `api_key` 为空/占位（复用 `hasUsableApiKey()` 判定）时，回填 `defaultProviderKey(id)` 并置 `enabled = TRUE`；config 为空则跳过（保持未配置，不报错）。完成后发 `ModelConfigChangedEvent` 触发 init probe。

### 3.3 原版改为「自带 key」

- `defaultProviderKey()` 不再为原版 `dashscope`/`deepseek` 返回 key → 原版保持空 key。
- 原版 `enabled` 维持全新库默认（`FALSE`）：只在「添加 Provider」抽屉里，用户添加并填自己的 key 后才启用。
- **不**改原版的 base_url / 模型集 / discovery 能力（用户用自己 key 时仍可用全量模型与发现）。

### 3.4 模型目录与默认模型

- 为三个默认版各镜像一份对应原版的 **builtin** 模型：
  - `dashscope-default` ← `provider='dashscope'` 的 builtin 模型
  - `dashscope-compat-default` ← `provider='dashscope-compat'` 的 builtin 模型
  - `deepseek-default` ← `provider='deepseek'` 的 builtin 模型
- 镜像实现用 `INSERT ... SELECT`（迁移）/ 对应 MERGE（种子），避免手维护逐行，保持与原版同步。
- 新模型行 id 用对原 id 的**固定偏移**生成（确定性、幂等；具体偏移值在实现计划中选定，确保落在未占用区间、无碰撞）。
- **默认聊天模型**：
  - 全新库（种子）：镜像出的 `dashscope-default/qwen-plus` 置 `is_default = TRUE`，原版 `dashscope/qwen-plus`（id 1000000001）置 `is_default = FALSE`。
  - 老库升级（迁移）：**条件迁移**，避免抢用户已选默认——见第 4 节。仅当某工作区当前默认仍是「未配置的原版」模型时，才把默认转到对应默认版；用户已配置原版并以其为默认时保留不动。
  - 不变量：**每个工作区恰好一个** `is_default = TRUE`。

### 3.5 key 只读 / 不可删 / 隐藏

- `ProviderInfoDTO` 增加计算字段 `managedKey`（boolean）：`toProviderInfo()` 中由 `MANAGED_DEFAULT_PROVIDER_IDS.contains(providerId)` 赋值。
- `updateProviderConfig()`：当 provider 属于受管集合时，**静默忽略** `apiKey` 与 `baseUrl` 的改动（UI 已禁用这两项，后端守卫为纵深防御；静默忽略而非抛错，避免连带阻断其它字段更新）。`generate_kwargs` 保留现有可改行为。
- 删除：`is_custom = FALSE` 已由 `deleteCustomProvider()` 抛 `err.llm.provider_builtin_readonly`，无需新增。
- 前端：`managedKey === true` 时
  - 隐藏/禁用 API Key 输入框，展示「由平台提供，不可修改」徽标；
  - 不显示删除入口（与现有 `isCustom` 判定一致即可）；
  - base_url 输入随 `freezeUrl` 已锁定。

### 3.6 数据交付（两条路径）

- **全新库**：4 个种子文件 `data-zh.sql` / `data-en.sql` / `data-mysql-zh.sql` / `data-mysql-en.sql`
  - 新增三个默认版 provider（`enabled=TRUE`）；
  - 镜像三组 builtin 模型；
  - 调整 `is_default`（原版 qwen-plus → FALSE，默认版 qwen-plus → TRUE）。
- **老库升级**：`V145__seed_default_cloud_provider_keys.sql`（h2 + mysql 双份）
  - 对**所有已存在工作区**（`SELECT DISTINCT workspace_id FROM mate_model_provider`）插入三个默认版 provider（幂等：先判存在）；
  - 镜像模型（`INSERT ... SELECT`，按工作区，幂等）；
  - 修正每工作区 `is_default`；
  - 幂等可重入（重复执行不产生重复行、不破坏已有数据）。

## 4. 边界与错误处理

- **平台 key 未配置**（config 为空/占位）：默认版 provider 保持 `UNCONFIGURED`，UI 正常提示「未配置」，不报错、不崩，不写入占位串。
- **老库里用户已在原版填了自己 key**：迁移**不动**原版（不清 key、不改 enabled、不改其 is_default 之外的状态）。迁移只新增默认版、并把 is_default 从原版 qwen-plus 转走——若用户当前默认模型正是原版 qwen-plus 且原版已配置自有 key，需保留其可用性（实现计划中明确：仅当原版未配置时才转移默认；已配置则保留用户当前默认，仅新增默认版而不抢默认）。
- **连接测试**：受管 provider 的 `test-connection` 用平台 key 正常工作。
- **env 覆盖**：平台 key 经 `application.yml` 占位读取，注入逻辑以 config 值为准，不在 SQL 里硬编码密钥。

## 5. 测试

- 后端单测
  - `defaultProviderKey()` 新映射（三个新 id 命中、原版返回 null）。
  - `updateProviderConfig()` 对受管 provider 拒改 `apiKey`/`baseUrl`。
  - `toProviderInfo()` 对受管 provider 输出 `managedKey=true`，原版 false。
  - 默认工作区启动注入：key 为空时回填且 enabled，config 为空时跳过。
- 数据路径
  - 全新库：三个默认版存在且 enabled，key 来自 config，每工作区恰好一个 is_default。
  - 老库升级 V145：所有工作区补齐默认版与镜像模型，is_default 唯一，重复执行幂等。
- 前端
  - 受管 provider 卡片/表单：key 输入禁用 + 徽标、删除入口隐藏、base_url 锁定。

## 6. 非目标（本次不做）

- 平台共享 key 的额度限流（已有 `ProviderTokenQuotaService`，可后续给默认版配默认额度）。
- embedding 默认版（种子里无挂在 `dashscope`/`deepseek` 的 embedding 模型；另有独立 spec `2026-06-17-embedding-default-multitenant-fix-design.md` 处理 embedding 多租户）。
- 原版 provider 的 base_url / 模型集 / discovery 行为变更。

## 7. 交付物清单

- 后端：`ModelProviderService`（受管集合常量、`defaultProviderKey()` 映射、`updateProviderConfig()` 守卫、`toProviderInfo()` 的 `managedKey`）、新增默认工作区 key 注入启动组件、`ProviderInfoDTO.managedKey`。
- 数据：4 个种子文件 + `V145` 迁移（h2 + mysql）。
- 前端：`composables/useProviderForm.ts`、`ProviderCard.vue`（按 `managedKey` 禁用/隐藏 key 输入与删除）、必要的 i18n 文案。
- 测试：后端单测 + 前端交互校验。
