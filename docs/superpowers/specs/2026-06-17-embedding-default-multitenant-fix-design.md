# 注册用户在「模型管理」看不到 Embedding 模型 — 根因与修复设计

日期:2026-06-17
状态:已确认根因(对抗式验证),范围=完整正确修复

## 症状

新注册的普通用户(`mate_user.role='user'`,是自己工作区的 owner,但**不是**全局 admin),打开「设置 → 模型 → Embedding」,显示"完全没有 embedding 模型"空状态。

## 根因(已用 dev 库地面真值 + 多 agent 对抗验证确认)

数据层完全正常:dev 库实测,注册用户的新工作区里 `text-embedding-v3` 已存在且 `is_default=TRUE / enabled=TRUE / deleted=0`,复制/迁移都对。

问题在读取层 `EmbeddingModelsSection.vue` 的 `loadAll()`:

```js
const [listRes, defaultRes, providerRes] = await Promise.all([
  modelApi.listByType('embedding'),   // @RequireWorkspaceRole("member") —— owner 可用，能返回 2 条
  modelApi.getDefaultEmbedding(),     // @RequireGlobalAdmin —— role=user 必 403
  modelApi.listProviders(),           // @RequireWorkspaceRole("admin") —— owner 可用
])
models.value = listRes.data || []     // ← Promise.all 因 403 整体 reject，这行永不执行
```

`Promise.all` 一个 reject 即整体 reject → `models.value` 永远停在 `[]` → 渲染空状态。角色层级 `owner(4) > admin(3) > member(2) > viewer(1)`,owner 满足 member/admin,**唯一 403 的是 `getDefaultEmbedding()` 的 `@RequireGlobalAdmin`**。

## 三层缺陷与修复

### 1. 前端「全有或全无」(must-fix,触发器)
`Promise.all` → `Promise.allSettled`,各结果独立取值,单个 403 不再清空整块。同模式三处:
- `views/Settings/Models/EmbeddingModelsSection.vue`(本 bug)
- `views/Settings/Models/MultimodalSidecarSection.vue`(非 admin 成员会无限 loading)
- `views/Settings/Models/index.vue`(Models 页 onMounted)

### 2. 后端越权收得过紧(must-fix)
embedding 模型本就按工作区隔离,服务层均按 `currentWorkspaceId()` 过滤。把以下端点从 `@RequireGlobalAdmin` 改为工作区角色:
- `GET  /models/embedding/default` → `@RequireWorkspaceRole("member")`
- `POST /models/embedding/default` → `@RequireWorkspaceRole("admin")`
- `POST /models/embedding/{modelId}/test` → `@RequireWorkspaceRole("member")`

另外:embedding 区块的"添加/删除"按钮走通用 `POST /models`、`DELETE /models/{id}`,这两个也从 `@RequireGlobalAdmin` 放权到 `@RequireWorkspaceRole("admin")`(服务层本就按工作区隔离),让 embedding 区块对 owner 完全可用。`GET /models/{id}`、`PUT /models/{id}`、`POST /models/{id}/default` 维持现状未动。

改动落点:主仓库 `/Users/linfeng/work/dev_work/mateclaw`(`dev` 分支工作树),与运行中的 app / dev 数据库一致,便于直接重启验证。

### 3. 默认 embedding 是全局设置(must-fix,架构)
`embedding.default.model.id` 是 `mate_system_setting` 的单行全局值(固定指向模板 id `1000001001`)。新工作区副本是新 id → 前端默认徽标永远匹配不上;且一个工作区改默认会影响所有租户。改为对齐 chat 的 `is_default` 机制(每工作区独立):

- 新增 `ModelConfigService.getDefaultEmbeddingModel()`:返回当前工作区 `model_type='embedding' AND is_default=true` 的行(无则 null)。
- 新增 `ModelConfigService.setDefaultEmbeddingModel(id)`:校验为 embedding 类型 → **类型隔离地**清除本工作区其它 embedding 默认 → 设该行 `is_default=true`。
- 新增 `ModelConfigService.clearDefaultEmbedding()`:清当前工作区 embedding 默认(供前端删除默认模型时的 `setDefaultEmbedding('')` 路径)。
- 把 `clearDefaultFlag()` 改为**类型感知** `clearDefaultFlag(modelType)`:chat 操作只清 chat/null 默认,embedding 操作只清 embedding 默认。修复"设 chat 默认会顺手清掉 embedding 默认"的潜在串类型 bug。`createModel/updateModel/setDefaultModel` 传入各自 `modelType`。
- Controller `getDefaultEmbedding/setDefaultEmbedding` 改用上述 service 方法,**不再读写**全局 system_setting;移除 controller 中 `systemSettingMapper` 字段及相关 import/常量。
- `WikiEmbeddingService.resolveSystemDefaultEmbedding()`:优先返回当前工作区 `is_default` 且 enabled 的 embedding(`getDefaultEmbeddingModel()`),无则回退到原全局指针重解析逻辑——让运行时与 UI 一致,同时保留旧行为兜底。

全局 `embedding.default.model.id` 行保留(兜底用),不做迁移。

## 测试(先红后绿)

- 后端 `ModelConfigServiceEmbeddingDefaultTest`(Mockito):
  - `getDefaultEmbeddingModel()` 返回 mapper 查到的工作区默认行。
  - `setDefaultEmbeddingModel(id)` 对目标行 `updateById(is_default=true)` 并发布变更事件。
  - `setDefaultEmbeddingModel(id)` 对非 embedding 模型抛异常。
- 前端 vitest(仓库 `?raw` 源码断言惯例):三个文件的 `loadAll/onMounted` 使用 `Promise.allSettled`,不再用裸 `Promise.all`。

## 验证

- 后端:`JAVA_HOME=JDK21 mvn -q -pl mateclaw-server test -Dtest=ModelConfigServiceEmbeddingDefaultTest`,并整体编译。
- 前端:`cd mateclaw-ui && npx vitest run`(相关用例)。
