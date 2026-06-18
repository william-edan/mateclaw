# 新用户注册：托管云端 provider 精简 + 默认版配额生效

日期：2026-06-18
状态：待评审

## 1. 背景与目标

「设置 → 模型管理 → 云端模型」对**新注册用户**应只提供两个托管（平台预置 key）云端 provider，并对其加上真实的 token 配额限制：

- **DashScope 默认**（`dashscope-default`）— 限额 **200 万 token**
- **DeepSeek 默认**（`deepseek-default`）— 限额 **300 万 token**

其余约 28 个云端 provider（DashScope、DashScope 兼容模式、DashScope 兼容模式 默认、DeepSeek 原版，以及 OpenAI / Anthropic / Gemini / Kimi / 智谱 / 火山 / 硅基流动 / MiniMax / ModelScope / xAI / OpenRouter / 各类 Coding Plan / OAuth 等）**不再种子化给新用户**。

本地 provider（Ollama / LM Studio / llama.cpp / MLX）**保留**——它们走用户自己的机器，没有平台成本。

两个默认版 provider 使用配置文件 `mateclaw.llm.default-provider-keys.dashscope` / `.deepseek` 注入的平台 key（**现有机制，不改**）。

## 2. 现状（探索结论）

注册链路：`AuthService.register()` → `WorkspaceService.create()` → `WorkspaceService.seedModelConfiguration()` → `ModelProviderService.seedWorkspaceModels(workspaceId)`。

- `seedWorkspaceModels`（`ModelProviderService.java:413`）把**默认工作区（`DEFAULT_WORKSPACE_ID = 1`）的全部 provider** 逐个 `copyProviderForWorkspace` 复制给新工作区，再 `modelConfigService.copyModelsToWorkspace`（`ModelConfigService.java:296`）复制**全部模型**，最后 `providerTokenQuotaService.ensureDefaultQuotas(workspaceId)`。
- `copyProviderForWorkspace`（`ModelProviderService.java:452`）对默认版 provider 已会调用 `applyRegistrationDefaultProviderKey` 注入平台 key 并 `enabled=true`——**这部分保留**。
- 配额（`ProviderTokenQuotaService.java`）的 `DEFAULT_LIMITS` 当前键是**原版** id：`dashscope`→2M、`deepseek`→3M。但聊天实际走 `dashscope-default`/`deepseek-default`，传入 `recordUsage`/`assertNotExhausted` 的 `providerId` 就是带 `-default` 的真实值，`isManagedProvider("dashscope-default")` 返回 `false`——**所以默认版当前根本没有配额拦截**。这正是要修的核心。
- V135 迁移已为所有存量工作区插入 `dashscope`(2M)/`deepseek`(3M) 配额行。

## 3. 方案选择

**采用 A1：代码层白名单过滤 + 配额常量改挂。** 无需 DB 迁移；默认工作区模板、已注册老用户的 provider 列表都不动；改动集中、可单测。

否决项：A2（配置驱动白名单）属 YAGNI，未要求运行期可配；A3（迁移脚本裁剪存量/种子数据）与"默认工作区和老用户不动"约束冲突。

## 4. 设计

### 4.1 种子白名单（仅新工作区注册路径）

在 `ModelProviderService` 新增常量：

```java
/** 新工作区注册时种子化的云端 provider 白名单（托管默认版）。 */
static final java.util.Set<String> REGISTRATION_CLOUD_PROVIDER_IDS =
        java.util.Set.of("dashscope-default", "deepseek-default");
```

`seedWorkspaceModels` 复制 provider 时按规则过滤，**保留条件**：

```
isLocal == TRUE  ||  REGISTRATION_CLOUD_PROVIDER_IDS.contains(providerId)
```

即：所有本地 provider 全留；云端只留两个默认版。复制过程中收集实际保留的 `providerId` 集合 `keptProviderIds`，传给模型复制做二次过滤。

### 4.2 模型复制同步过滤

`copyModelsToWorkspace` 当前复制源工作区**全部**模型，会产生指向已被剔除 provider 的孤儿模型。新增按 provider 过滤的重载：

```java
public void copyModelsToWorkspace(Long sourceWorkspaceId, Long targetWorkspaceId,
                                  java.util.Set<String> allowedProviderIds)
```

只复制 `allowedProviderIds.contains(model.getProvider())` 的模型；旧的无参重载保留（复制全部）供其它调用方使用。`seedWorkspaceModels` 改调带 `keptProviderIds` 的重载。

**默认模型保障**：默认工作区经 V145 迁移后，默认 chat 模型为 `dashscope-default/qwen-plus`、默认 embedding 也挂在 `dashscope-default`，均在白名单内 → 新用户复制后 `is_default` 标记随之带过来，仍有默认模型。该不变量由测试断言守护（见 §7.2）。若默认工作区被管理员改成了非白名单 provider 的默认模型这一边界情况，处理留待计划阶段评估（注意 `ensureDefaultExists()` 依赖 `currentWorkspaceId()`，注册期工作区上下文未必指向新工作区，不能直接照搬）。

### 4.3 配额改挂到默认版 + 豁免管理员工作区

`ProviderTokenQuotaService.DEFAULT_LIMITS` 改为：

```java
DEFAULT_LIMITS.put("dashscope-default", 2_000_000L);
DEFAULT_LIMITS.put("deepseek-default", 3_000_000L);
```

（移除原版 `dashscope`/`deepseek` 两个键。）

效果：
- 新工作区 `ensureDefaultQuotas` 创建 `dashscope-default`(2M)/`deepseek-default`(3M) 两行配额。
- 聊天 `recordUsage`/`assertNotExhausted` 传入 `dashscope-default`/`deepseek-default` 时 `isManagedProvider` 命中 → **配额真正生效**。

**豁免管理员（默认）工作区**：配额是全局逻辑，老用户/管理员工作区里也有这两个 provider，会被同样限额——按决策**全局生效但豁免 `DEFAULT_WORKSPACE_ID`(=1)**。在 `getQuota` / `assertNotExhausted` / `recordUsage` / `ensureDefaultQuotas` 入口处加守卫：`workspaceId == ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID` 时直接跳过（`getQuota` 返回 `null`，其余 no-op）。

**对存量数据的影响**：V135 留下的 `dashscope`/`deepseek` 配额行在改挂后变为惰性无效（`isManagedProvider` 不再认它们，永不读写），无害；老用户首次使用默认版时按 2M/3M 懒创建 `-default` 配额行。**无需 DB 迁移**（旧行清理可作为独立后续，不在本次范围）。

### 4.4 不改动项

- 平台 key 注入（`applyRegistrationDefaultProviderKey` / `DefaultProviderKeyBootstrap` / `mateclaw.llm.default-provider-keys`）：现成可用，沿用。
- 默认工作区种子数据（`data-mysql-zh.sql`）：保留全部 provider，作为模板与管理员可用集。
- 已注册老用户工作区的 provider 列表：不动。
- 前端：无需改动——新用户云端区天然只剩两个 provider；配额展示接口对 `-default` provider 自然返回数据。

## 5. 边界与风险

- **顺序依赖**：`keptProviderIds` 必须在 provider 复制阶段如实收集（按"实际写入新工作区"的集合），再驱动模型过滤；避免本地 provider 集合写死（本地 provider id 是动态的，以 `isLocal` 判定）。
- **embedding 默认**：确认 `dashscope-default` 下有 embedding 模型被复制（V145 已挂载），否则新用户知识库 embedding 不可用。测试覆盖。
- **管理员豁免**：守卫只认 `workspaceId == 1`；多个"管理员"概念不存在，平台默认工作区即 id=1。
- **老用户行为变化**：老用户用默认版会开始受 2M/3M 限额（管理员工作区除外）——这是已确认接受的全局行为。

## 6. 受影响文件

- `mateclaw-server/.../llm/service/ModelProviderService.java`：新增白名单常量；`seedWorkspaceModels` 过滤 + 收集 `keptProviderIds`。
- `mateclaw-server/.../llm/service/ModelConfigService.java`：新增按 provider 过滤的 `copyModelsToWorkspace` 重载。
- `mateclaw-server/.../llm/service/ProviderTokenQuotaService.java`：`DEFAULT_LIMITS` 改挂 `-default`；四个入口加管理员工作区守卫。

## 7. 测试

以 TDD 推进，覆盖：

1. **种子白名单**：新工作区注册后，云端 provider 恰为 `dashscope-default` + `deepseek-default`；本地 provider 全部保留；原版/兼容/其它云端 provider 均不存在。
2. **模型过滤**：新工作区模型只属于被保留的 provider，无孤儿模型；存在默认 chat 模型与默认 embedding 模型。
3. **key 注入**：两个默认版 provider `enabled=true` 且 api_key 等于配置文件注入值。
4. **配额生效**：新工作区存在 `dashscope-default`(2M)/`deepseek-default`(3M) 配额行；用满后 `assertNotExhausted` 抛 429；`recordUsage` 正确累加。
5. **管理员豁免**：`DEFAULT_WORKSPACE_ID` 上 `getQuota` 返回 null、`assertNotExhausted` 不抛、`recordUsage` 不计。
6. 更新现有 `DefaultCloudProviderSeedIT` 与任何断言旧 `dashscope`/`deepseek` 配额键的测试。

## 8. 范围外

- 清理 V135 遗留的 `dashscope`/`deepseek` 惰性配额行。
- 配额充值 / 运行期可配限额 / 按套餐分层。
- 老用户 provider 列表的回溯清理。
