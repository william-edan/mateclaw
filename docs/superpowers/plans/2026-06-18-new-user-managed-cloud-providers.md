# 新用户托管云端 provider 精简 + 默认版配额生效 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新用户注册时云端模型只种子化 `dashscope-default`(限额 200 万 token) 与 `deepseek-default`(限额 300 万 token) 两个托管 provider，本地 provider 全保留，且配额对默认版真正生效（管理员工作区豁免）。

**Architecture:** 三处 Java 改动 + 测试。(1) `ModelProviderService.seedWorkspaceModels` 复制模板时按白名单过滤（本地 provider 或两个默认版云端），并把保留集合传给模型复制；(2) `ModelConfigService.copyModelsToWorkspace` 新增按 provider 过滤的重载；(3) `ProviderTokenQuotaService.DEFAULT_LIMITS` 改挂到 `-default` id 使配额命中，并在四个入口豁免 `DEFAULT_WORKSPACE_ID`(=1)。不改种子数据、不改默认工作区模板、不写 DB 迁移、不改 key 注入。

**Tech Stack:** Java 21 + Spring Boot 3 + MyBatis-Plus + Flyway + JUnit 5 + Mockito + H2(测试)。

---

## 环境约定（所有任务通用）

- **Worktree 根目录（所有命令在此执行）：** `/Users/linfeng/work/dev_work/mateclaw/.claude/worktrees/thirsty-mclean-f1664f`
- **构建必须用 JDK 21**（默认 mvn 走 JDK 23 会让 Lombok 静默失效报"找不到符号"）。
- **单个测试类的标准命令模板**（在 worktree 根目录执行）：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='<ClassName>' -Dsurefire.failIfNoSpecifiedTests=false
```

- surefire 用默认包含规则（`*Test`/`*Tests`），**不会自动跑 `*IT`**。`*IT` 命名的端到端测试必须用 `-Dtest=<ClassIT>` 显式运行。
- **必须用 `-Dsurefire.failIfNoSpecifiedTests=false`**（不是 `-DfailIfNoTests`）：`-am` 会把上游模块（`mateclaw-plugin-api`）纳入 reactor，对它们执行 `-Dtest` 过滤时无匹配类会抛 "No tests matching pattern ... were executed!" 直接 BUILD FAILURE。此开关让"指定测试在某模块无匹配"不致失败（已实测：加该开关后跑 `ProviderTokenQuotaServiceTest,ModelProviderServiceWorkspaceIsolationTest` → BUILD SUCCESS, Tests run: 10）。
- 数据种子文件：MySQL 运行期加载 `db/data-mysql-zh.sql`，H2 测试链路加载 `db/data-zh.sql`（两者 provider_id 一致）；本计划的 IT 断言基于 H2 的 `data-zh.sql` + `db/migration/h2/V145`。

## 文件结构（本计划涉及的文件与职责）

| 文件 | 动作 | 职责 |
|---|---|---|
| `mateclaw-server/src/main/java/vip/mate/llm/service/ProviderTokenQuotaService.java` | 改 | 配额限额改挂 `-default`；四入口豁免默认工作区 |
| `mateclaw-server/src/main/java/vip/mate/llm/service/ModelConfigService.java` | 改 | 新增按 provider 过滤的 `copyModelsToWorkspace` 重载 |
| `mateclaw-server/src/main/java/vip/mate/llm/service/ModelProviderService.java` | 改 | 注册种子白名单过滤 + 把保留集合传给模型复制 |
| `mateclaw-server/src/test/java/vip/mate/llm/service/ProviderTokenQuotaServiceTest.java` | 改 | 配额改挂 + 豁免的单测 |
| `mateclaw-server/src/test/java/vip/mate/llm/service/ModelProviderServiceWorkspaceIsolationTest.java` | 改 | 种子白名单过滤的单测 |
| `mateclaw-server/src/test/java/vip/mate/llm/service/ModelConfigServiceCopyModelsTest.java` | 建 | 模型复制过滤重载的单测 |
| `mateclaw-server/src/test/java/vip/mate/llm/service/NewWorkspaceSeedFilterIT.java` | 建 | 新工作区种子化端到端（H2）验证 |

---

## Task 0: 基线 — 确认 JDK 21 构建 + 现有相关测试通过

确保 worktree 能用 JDK 21 编译，且本计划将要修改的两个现有测试类当前是绿的（改动后它们会先变红，再修绿）。

**Files:** 无改动。

- [ ] **Step 1: 跑现有相关单测，确认基线通过**

Run（worktree 根目录）：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test \
  -Dtest='ProviderTokenQuotaServiceTest,ModelProviderServiceWorkspaceIsolationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS，两个测试类全部通过（这是改动前的绿基线）。若报 Lombok "找不到符号"，说明 JAVA_HOME 没生效，检查 JDK 21 路径。

---

## Task 1: 配额限额改挂到默认版 + 豁免管理员工作区

把 `DEFAULT_LIMITS` 的键从原版 `dashscope`/`deepseek` 改为 `dashscope-default`(2M)/`deepseek-default`(3M)，让聊天实际传入的 `-default` providerId 能命中配额逻辑；并在 `getQuota`/`recordUsage`/`ensureDefaultQuotas` 入口豁免 `DEFAULT_WORKSPACE_ID`（`assertNotExhausted` 走 `getQuota` 自动豁免）。

**Files:**
- Modify: `mateclaw-server/src/test/java/vip/mate/llm/service/ProviderTokenQuotaServiceTest.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/service/ProviderTokenQuotaService.java`

- [ ] **Step 1: 改测试到新预期（先让它失败）**

把 `ProviderTokenQuotaServiceTest.java` 的全文替换为：

```java
package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ProviderTokenQuotaEntity;
import vip.mate.llm.repository.ProviderTokenQuotaMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProviderTokenQuotaServiceTest {

    private ProviderTokenQuotaMapper mapper;
    private ProviderTokenQuotaService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ProviderTokenQuotaMapper.class);
        service = new ProviderTokenQuotaService(mapper);
    }

    @Test
    void ensureDefaultQuotasCreatesDefaultVersionRows() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.ensureDefaultQuotas(20L);

        var captor = org.mockito.ArgumentCaptor.forClass(ProviderTokenQuotaEntity.class);
        verify(mapper, times(2)).insert(captor.capture());

        ProviderTokenQuotaEntity dashscope = captor.getAllValues().get(0);
        assertEquals(20L, dashscope.getWorkspaceId());
        assertEquals("dashscope-default", dashscope.getProviderId());
        assertEquals(2_000_000L, dashscope.getLimitTokens());
        assertEquals(0L, dashscope.getUsedTokens());

        ProviderTokenQuotaEntity deepseek = captor.getAllValues().get(1);
        assertEquals("deepseek-default", deepseek.getProviderId());
        assertEquals(3_000_000L, deepseek.getLimitTokens());
    }

    @Test
    void assertNotExhaustedRejectsManagedDefaultProviderAtLimit() {
        ProviderTokenQuotaEntity quota = quota("dashscope-default", 2_000_000L, 2_000_000L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.assertNotExhausted(20L, "dashscope-default"));

        assertEquals("额度已用完，请在 设置-模型管理 中充值或切换模型", ex.getMessage());
        assertEquals(429, ex.getCode());
    }

    @Test
    void recordUsageAddsPromptAndCompletionTokens() {
        ProviderTokenQuotaEntity quota = quota("deepseek-default", 3_000_000L, 12L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota);

        service.recordUsage(20L, "deepseek-default", 30, 40);

        verify(mapper).incrementUsedTokens(20L, "deepseek-default", 70L);
        verify(mapper, never()).updateById(any(ProviderTokenQuotaEntity.class));
    }

    @Test
    void unmanagedProvidersDoNothing() {
        // 第三方云端、以及现在已不再受管的原版 dashscope/deepseek 都不应触发任何 mapper 调用
        service.assertNotExhausted(20L, "openai");
        service.recordUsage(20L, "openai", 10, 20);
        service.assertNotExhausted(20L, "dashscope");
        service.recordUsage(20L, "deepseek", 10, 20);

        verifyNoInteractions(mapper);
    }

    @Test
    void defaultWorkspaceIsExemptFromQuota() {
        long admin = ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID; // 1L

        assertNull(service.getQuota(admin, "dashscope-default"),
                "管理员工作区不展示配额");
        // 即使没有任何 stub 也不抛、不查 mapper —— 说明被豁免，根本没走到 mapper
        service.assertNotExhausted(admin, "dashscope-default");
        service.recordUsage(admin, "dashscope-default", 1000, 2000);
        service.ensureDefaultQuotas(admin);

        verifyNoInteractions(mapper);
    }

    @Test
    void recordUsageLazilyCreatesDefaultVersionQuotaRow() {
        // 非默认工作区(老用户)首次命中默认版：findQuota 返回 null → 懒创建 2M 限额行后再累加
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.recordUsage(20L, "dashscope-default", 100, 200);

        var captor = org.mockito.ArgumentCaptor.forClass(ProviderTokenQuotaEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("dashscope-default", captor.getValue().getProviderId());
        assertEquals(2_000_000L, captor.getValue().getLimitTokens());
        verify(mapper).incrementUsedTokens(20L, "dashscope-default", 300L);
    }

    private static ProviderTokenQuotaEntity quota(String providerId, long limit, long used) {
        ProviderTokenQuotaEntity quota = new ProviderTokenQuotaEntity();
        quota.setWorkspaceId(20L);
        quota.setProviderId(providerId);
        quota.setLimitTokens(limit);
        quota.setUsedTokens(used);
        return quota;
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

Run:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='ProviderTokenQuotaServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL。`ensureDefaultQuotasCreatesDefaultVersionRows` 断言 `dashscope-default` 但当前代码插入的是 `dashscope`；`defaultWorkspaceIsExemptFromQuota` 因当前无豁免会与 mapper 交互 → 失败。

- [ ] **Step 3: 实现 — 改 `DEFAULT_LIMITS` 并加豁免**

在 `ProviderTokenQuotaService.java` 中：

(a) 替换静态初始化块：

```java
    static {
        DEFAULT_LIMITS.put("dashscope-default", 2_000_000L);
        DEFAULT_LIMITS.put("deepseek-default", 3_000_000L);
    }
```

(b) 在 `isManagedProvider` 方法下方新增私有 helper（`ModelWorkspaceResolver` 与本类同包，无需 import）：

```java
    /** 默认（管理员/平台模板）工作区豁免配额限制。 */
    private boolean isDefaultWorkspace(Long workspaceId) {
        return workspaceId != null && workspaceId == ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID;
    }
```

(c) `ensureDefaultQuotas` 的守卫加上豁免：

```java
    @Transactional
    public void ensureDefaultQuotas(Long workspaceId) {
        if (workspaceId == null || isDefaultWorkspace(workspaceId)) {
            return;
        }
        for (Map.Entry<String, Long> entry : DEFAULT_LIMITS.entrySet()) {
            ensureQuota(workspaceId, entry.getKey(), entry.getValue());
        }
    }
```

(d) `getQuota` 的守卫加上豁免（`assertNotExhausted` 委托 `getQuota`，自动豁免，无需单独改）：

```java
    public ProviderTokenQuotaDTO getQuota(Long workspaceId, String providerId) {
        if (workspaceId == null || isDefaultWorkspace(workspaceId) || !isManagedProvider(providerId)) {
            return null;
        }
        ProviderTokenQuotaEntity quota = findQuota(workspaceId, providerId);
        if (quota == null) {
            quota = ensureQuota(workspaceId, providerId, DEFAULT_LIMITS.get(providerId));
        }
        return ProviderTokenQuotaDTO.from(quota);
    }
```

(e) `recordUsage` 的守卫加上豁免：

```java
    @Transactional
    public void recordUsage(Long workspaceId, String providerId, int promptTokens, int completionTokens) {
        if (workspaceId == null || isDefaultWorkspace(workspaceId) || !isManagedProvider(providerId)) {
            return;
        }
        long delta = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        if (delta <= 0) {
            return;
        }
        ProviderTokenQuotaEntity quota = findQuota(workspaceId, providerId);
        if (quota == null) {
            ensureQuota(workspaceId, providerId, DEFAULT_LIMITS.get(providerId));
        }
        mapper.incrementUsedTokens(workspaceId, providerId, delta);
    }
```

- [ ] **Step 4: 跑测试，确认通过**

Run:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='ProviderTokenQuotaServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS，6 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/llm/service/ProviderTokenQuotaService.java \
        mateclaw-server/src/test/java/vip/mate/llm/service/ProviderTokenQuotaServiceTest.java
git commit -m "feat(llm): 配额限额改挂默认版 provider 并豁免管理员工作区

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `copyModelsToWorkspace` 新增按 provider 过滤的重载

新增三参重载 `copyModelsToWorkspace(source, target, allowedProviderIds)`，只复制 provider 在白名单内的模型；`allowedProviderIds == null` 时复制全部（保持旧行为）。原两参方法委托到三参（DRY）。

**Files:**
- Create: `mateclaw-server/src/test/java/vip/mate/llm/service/ModelConfigServiceCopyModelsTest.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/service/ModelConfigService.java:296-314`

- [ ] **Step 1: 写失败测试**

新建 `ModelConfigServiceCopyModelsTest.java`：

```java
package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelConfigServiceCopyModelsTest {

    private ModelConfigMapper mapper;
    private ModelConfigService service;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                ModelConfigEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ModelConfigMapper.class);
        service = new ModelConfigService(mapper, mock(ApplicationEventPublisher.class),
                mock(ModelCapabilityService.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void copyOnlyIncludesAllowedProviders() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())                        // 目标工作区为空 → 继续
                .thenReturn(List.of(                          // 源模板
                        model("dashscope-default", "qwen-plus"),
                        model("deepseek-default", "deepseek-chat"),
                        model("dashscope", "qwen-max"),
                        model("openai", "gpt-4o")));

        service.copyModelsToWorkspace(1L, 20L, Set.of("dashscope-default", "deepseek-default"));

        org.mockito.ArgumentCaptor<ModelConfigEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertEquals(Set.of("dashscope-default", "deepseek-default"),
                captor.getAllValues().stream().map(ModelConfigEntity::getProvider)
                        .collect(Collectors.toSet()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullAllowedSetCopiesEverything() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        model("dashscope-default", "qwen-plus"),
                        model("openai", "gpt-4o")));

        service.copyModelsToWorkspace(1L, 20L, null);

        verify(mapper, times(2)).insert(any(ModelConfigEntity.class));
    }

    private static ModelConfigEntity model(String provider, String modelName) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setProvider(provider);
        m.setModelName(modelName);
        m.setName(modelName);
        m.setWorkspaceId(1L);
        m.setModelType("chat");
        m.setEnabled(true);
        return m;
    }
}
```

- [ ] **Step 2: 跑测试，确认失败（编译错误：三参重载不存在）**

Run:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='ModelConfigServiceCopyModelsTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL（编译失败，`copyModelsToWorkspace(Long,Long,Set)` 方法不存在）。

- [ ] **Step 3: 实现重载**

把 `ModelConfigService.java` 现有的 `copyModelsToWorkspace(Long, Long)`（296-314 行）整体替换为下面两个方法：

```java
    public void copyModelsToWorkspace(Long sourceWorkspaceId, Long targetWorkspaceId) {
        copyModelsToWorkspace(sourceWorkspaceId, targetWorkspaceId, null);
    }

    /**
     * 复制源工作区模型到目标工作区。{@code allowedProviderIds} 非空时只复制 provider
     * 在该集合内的模型（用于注册种子化只保留白名单 provider 的模型，避免产生指向
     * 未种子化 provider 的孤儿模型）；为 {@code null} 时复制全部（旧行为）。
     */
    public void copyModelsToWorkspace(Long sourceWorkspaceId, Long targetWorkspaceId,
                                      java.util.Set<String> allowedProviderIds) {
        if (sourceWorkspaceId == null || targetWorkspaceId == null || sourceWorkspaceId.equals(targetWorkspaceId)) {
            return;
        }
        List<ModelConfigEntity> existing = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getWorkspaceId, targetWorkspaceId)
                .last("LIMIT 1"));
        if (!existing.isEmpty()) {
            return;
        }
        List<ModelConfigEntity> templates = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getWorkspaceId, sourceWorkspaceId)
                .orderByDesc(ModelConfigEntity::getIsDefault)
                .orderByAsc(ModelConfigEntity::getProvider)
                .orderByAsc(ModelConfigEntity::getName));
        for (ModelConfigEntity template : templates) {
            if (allowedProviderIds != null && !allowedProviderIds.contains(template.getProvider())) {
                continue;
            }
            modelConfigMapper.insert(copyModelForWorkspace(template, targetWorkspaceId));
        }
    }
```

- [ ] **Step 4: 跑测试，确认通过**

Run:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='ModelConfigServiceCopyModelsTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS，2 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/llm/service/ModelConfigService.java \
        mateclaw-server/src/test/java/vip/mate/llm/service/ModelConfigServiceCopyModelsTest.java
git commit -m "feat(llm): copyModelsToWorkspace 支持按 provider 白名单过滤

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: 注册种子白名单过滤（ModelProviderService）

`seedWorkspaceModels` 复制模板时只保留「本地 provider」或「`dashscope-default`/`deepseek-default`」，收集实际保留的 providerId，传给三参 `copyModelsToWorkspace`。

**Files:**
- Modify: `mateclaw-server/src/test/java/vip/mate/llm/service/ModelProviderServiceWorkspaceIsolationTest.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/llm/service/ModelProviderService.java:54`（新增常量）, `:413-442`（过滤）

- [ ] **Step 1: 改测试到新预期（先让它失败）**

在 `ModelProviderServiceWorkspaceIsolationTest.java` 中做两处修改：

(1) 把现有的 `seedWorkspaceModelsInjectsConfiguredDefaultKeysForNewRegistrations` 方法（113-146 行，含 `@Test` 注解行；建议按方法名定位而非行号）整体替换为：

```java
    @Test
    void seedWorkspaceModelsFiltersToManagedDefaultsAndLocalProviders() {
        // 新设计：注册种子化只保留本地 provider + 托管默认版（dashscope-default / deepseek-default）；
        // 原版 dashscope、其它云端 openai 一律不种子化。
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        provider("dashscope-default", 1L, ""),
                        provider("deepseek-default", 1L, ""),
                        provider("dashscope", 1L, ""),       // 原版云端 → 不种子
                        providerLocal("ollama", 1L),         // 本地 → 保留
                        provider("openai", 1L, "")));        // 其它云端 → 不种子

        service.seedWorkspaceModels(20L);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ModelProviderEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ModelProviderEntity.class);
        verify(providerMapper, times(3)).insert(captor.capture());
        List<ModelProviderEntity> copies = captor.getAllValues();

        assertEquals(java.util.Set.of("dashscope-default", "deepseek-default", "ollama"),
                copies.stream().map(ModelProviderEntity::getProviderId)
                        .collect(java.util.stream.Collectors.toSet()),
                "注册种子只保留本地 provider + 两个默认版云端");

        ModelProviderEntity dashscopeDefault = copyByProvider(copies, "dashscope-default");
        assertEquals(20L, dashscopeDefault.getWorkspaceId());
        assertEquals("sk-test-dashscope-default", dashscopeDefault.getApiKey());
        assertTrue(dashscopeDefault.getEnabled());

        ModelProviderEntity deepseekDefault = copyByProvider(copies, "deepseek-default");
        assertEquals(20L, deepseekDefault.getWorkspaceId());
        assertEquals("sk-test-deepseek-default", deepseekDefault.getApiKey());
        assertTrue(deepseekDefault.getEnabled());

        // 模型复制按实际保留的 provider 集合过滤
        verify(modelConfigService).copyModelsToWorkspace(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(
                        java.util.Set.of("dashscope-default", "deepseek-default", "ollama")));

        verify(quotaService).ensureDefaultQuotas(20L);
    }
```

(2) 把 `seedWorkspaceModelsPublishesModelConfigChangedEventToTriggerProbe`（148-162 行）里第二个 `thenReturn` 的模板从原版 `dashscope` 改为可种子化的 `dashscope-default`，使确有 provider 被种子化（事件仍应触发）：

```java
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(provider("dashscope-default", 1L, "")));
```

(3) 在文件末尾的 helper 区（`copyByProvider` 方法附近）新增本地 provider 构造器：

```java
    private static ModelProviderEntity providerLocal(String id, Long workspaceId) {
        ModelProviderEntity p = provider(id, workspaceId, "");
        p.setIsLocal(true);
        return p;
    }
```

> 注：`seedWorkspaceModelsSkipsEventWhenWorkspaceAlreadySeeded`（164-174 行）不用改——它在"目标工作区已有 provider"时提前返回，不会走到模板过滤。

- [ ] **Step 2: 跑测试，确认失败**

Run:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='ModelProviderServiceWorkspaceIsolationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL。当前 `seedWorkspaceModels` 不过滤、插入全部 5 个，`times(3)` 与 `copyModelsToWorkspace(eq,eq,eq)` 三参校验都会失败。

- [ ] **Step 3: 实现 — 加白名单常量 + 过滤 + 传保留集合**

在 `ModelProviderService.java`：

(a) 在 `MANAGED_DEFAULT_PROVIDER_IDS` 常量（54-55 行附近）下方新增：

```java
    /**
     * 新工作区注册时种子化的云端 provider 白名单（仅托管「默认版」）。本地 provider
     * （is_local=TRUE）始终保留；其余所有云端 provider 一律不种子化给新用户。
     */
    static final java.util.Set<String> REGISTRATION_CLOUD_PROVIDER_IDS = java.util.Set.of(
            "dashscope-default", "deepseek-default");
```

(b) 把 `seedWorkspaceModels` 里复制模板的循环及其后两行（430-435 行）替换为：

```java
        java.util.Set<String> keptProviderIds = new java.util.LinkedHashSet<>();
        for (ModelProviderEntity template : templates) {
            if (!shouldSeedForRegistration(template)) {
                continue;
            }
            ModelProviderEntity copy = copyProviderForWorkspace(template, workspaceId);
            modelProviderMapper.insert(copy);
            keptProviderIds.add(copy.getProviderId());
        }
        providerTokenQuotaService.ensureDefaultQuotas(workspaceId);
        modelConfigService.copyModelsToWorkspace(
                ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID, workspaceId, keptProviderIds);
```

（其后的 `eventPublisher.publishEvent(new ModelConfigChangedEvent("workspace-seeded"));` 一行保持不变。）

(c) 在 `copyProviderForWorkspace` 方法（452 行）上方新增 helper：

```java
    /** 注册种子白名单：本地 provider 全留；云端只留托管默认版。 */
    private boolean shouldSeedForRegistration(ModelProviderEntity template) {
        return Boolean.TRUE.equals(template.getIsLocal())
                || REGISTRATION_CLOUD_PROVIDER_IDS.contains(template.getProviderId());
    }
```

- [ ] **Step 4: 跑测试，确认通过**

Run:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='ModelProviderServiceWorkspaceIsolationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS，该类全部测试通过。

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/llm/service/ModelProviderService.java \
        mateclaw-server/src/test/java/vip/mate/llm/service/ModelProviderServiceWorkspaceIsolationTest.java
git commit -m "feat(llm): 新用户注册种子化只保留本地 provider + 两个默认版云端

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: 端到端验证（H2 IT）— 真实种子数据下的新工作区

用真实 data-zh.sql + Flyway(V145 镜像) 在 H2 上跑一遍：对一个全新工作区(id=2)调用 `seedWorkspaceModels`，断言云端只剩两个默认版、本地保留、无孤儿模型、默认聊天模型在 `dashscope-default`、配额行正确、管理员工作区豁免。

**Files:**
- Create: `mateclaw-server/src/test/java/vip/mate/llm/service/NewWorkspaceSeedFilterIT.java`

- [ ] **Step 1: 写 IT**

新建 `NewWorkspaceSeedFilterIT.java`：

```java
package vip.mate.llm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：在真实 data-zh.sql 种子 + Flyway(含 V145 模型镜像) 之上，对一个全新工作区(id=2)
 * 调用 seedWorkspaceModels，验证注册种子白名单 + 默认版配额 + 管理员豁免的整链路。
 * 因 surefire 默认不收 *IT，需用 -Dtest=NewWorkspaceSeedFilterIT 显式运行。
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:new_ws_seed_it_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.llm.default-provider-keys.dashscope=sk-test-dashscope-platform-key",
        "mateclaw.llm.default-provider-keys.deepseek=sk-test-deepseek-platform-key"
})
class NewWorkspaceSeedFilterIT {

    private static final long NEW_WS = 2L;

    @Autowired
    private ModelProviderService modelProviderService;

    @Autowired
    private ProviderTokenQuotaService quotaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedNewWorkspace() {
        // 幂等：已种子化则直接返回，可被多个 @Test 复用同一份 H2 数据
        modelProviderService.seedWorkspaceModels(NEW_WS);
    }

    @Test
    @DisplayName("新工作区云端 provider 恰为 dashscope-default + deepseek-default")
    void cloudProvidersAreOnlyTheTwoManagedDefaults() {
        List<String> cloud = jdbcTemplate.queryForList(
                "SELECT provider_id FROM mate_model_provider " +
                "WHERE workspace_id = ? AND is_local = FALSE ORDER BY provider_id",
                String.class, NEW_WS);
        assertEquals(List.of("dashscope-default", "deepseek-default"), cloud,
                "新用户云端只应有两个托管默认版 provider，实际: " + cloud);
    }

    @Test
    @DisplayName("新工作区保留了本地 provider")
    void localProvidersArePreserved() {
        Integer localCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_provider WHERE workspace_id = ? AND is_local = TRUE",
                Integer.class, NEW_WS);
        assertNotNull(localCount);
        assertTrue(localCount > 0, "本地 provider（Ollama 等）应被保留");
    }

    @Test
    @DisplayName("新工作区没有指向未种子 provider 的孤儿模型")
    void noOrphanModels() {
        Integer orphan = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_config m WHERE m.workspace_id = ? AND m.deleted = 0 " +
                "AND m.provider NOT IN (SELECT provider_id FROM mate_model_provider WHERE workspace_id = ?)",
                Integer.class, NEW_WS, NEW_WS);
        assertEquals(0, orphan, "不应存在指向未种子化 provider 的模型");

        Integer droppedCloud = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_model_config WHERE workspace_id = ? AND deleted = 0 " +
                "AND provider IN ('dashscope','dashscope-compat','dashscope-compat-default','deepseek','openai')",
                Integer.class, NEW_WS);
        assertEquals(0, droppedCloud, "被剔除的云端 provider 不应有任何模型残留");
    }

    @Test
    @DisplayName("新工作区默认聊天模型挂在 dashscope-default 上")
    void defaultChatModelIsOnDashscopeDefault() {
        List<Map<String, Object>> defaults = jdbcTemplate.queryForList(
                "SELECT provider, model_name FROM mate_model_config " +
                "WHERE workspace_id = ? AND is_default = TRUE AND deleted = 0 " +
                "  AND (model_type IS NULL OR model_type = 'chat')",
                NEW_WS);
        assertEquals(1, defaults.size(), "应有且仅有一个默认聊天模型，实际: " + defaults);
        assertEquals("dashscope-default", defaults.get(0).get("provider"));
    }

    @Test
    @DisplayName("新工作区默认 embedding 模型挂在 dashscope-default 上")
    void defaultEmbeddingModelIsOnDashscopeDefault() {
        List<Map<String, Object>> defaults = jdbcTemplate.queryForList(
                "SELECT provider, model_name FROM mate_model_config " +
                "WHERE workspace_id = ? AND is_default = TRUE AND deleted = 0 AND model_type = 'embedding'",
                NEW_WS);
        assertEquals(1, defaults.size(), "应有且仅有一个默认 embedding 模型，实际: " + defaults);
        assertEquals("dashscope-default", defaults.get(0).get("provider"),
                "默认 embedding 必须随白名单复制到 dashscope-default，否则新用户知识库 embedding 不可用");
    }

    @Test
    @DisplayName("新工作区配额行：dashscope-default=200万, deepseek-default=300万")
    void quotaRowsSeededWithRequestedLimits() {
        Long ds = jdbcTemplate.queryForObject(
                "SELECT limit_tokens FROM mate_provider_token_quota WHERE workspace_id = ? AND provider_id = 'dashscope-default'",
                Long.class, NEW_WS);
        Long de = jdbcTemplate.queryForObject(
                "SELECT limit_tokens FROM mate_provider_token_quota WHERE workspace_id = ? AND provider_id = 'deepseek-default'",
                Long.class, NEW_WS);
        assertEquals(2_000_000L, ds);
        assertEquals(3_000_000L, de);
    }

    @Test
    @DisplayName("管理员工作区(id=1)豁免配额，新工作区受限")
    void adminWorkspaceExemptNewWorkspaceLimited() {
        assertNull(quotaService.getQuota(1L, "dashscope-default"),
                "管理员工作区应豁免配额");
        assertNotNull(quotaService.getQuota(NEW_WS, "dashscope-default"),
                "新工作区应有可见配额");
    }
}
```

- [ ] **Step 2: 跑 IT，确认通过**

Run（`*IT` 需显式指定）：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='NewWorkspaceSeedFilterIT' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS，7 个断言全绿。若 `defaultChatModelIsOnDashscopeDefault` / `defaultEmbeddingModelIsOnDashscopeDefault` 或孤儿检查失败，说明模型过滤集合或 V145 镜像数据有偏差——回看 Task 3 的 `keptProviderIds` 是否正确传入、以及源工作区默认模型是否确在 `dashscope-default`。

- [ ] **Step 3: 提交**

```bash
git add mateclaw-server/src/test/java/vip/mate/llm/service/NewWorkspaceSeedFilterIT.java
git commit -m "test(llm): 新工作区种子白名单+默认版配额+管理员豁免 端到端 IT

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: 回归 — 既有套件 + 既有 IT 不被破坏

确认默认工作区路径（保留全部 provider）未受影响：既有的 `DefaultCloudProviderSeedIT` 仍应全绿；llm 模块单测套件全绿。

**Files:** 无改动。

- [ ] **Step 1: 跑既有端到端 IT（默认工作区断言不应回归）**

Run:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test -Dtest='DefaultCloudProviderSeedIT' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS。默认工作区(id=1) 仍保留三个受管默认版 + 原版，且默认模型在 dashscope-default——本计划未改种子数据与默认工作区路径，应不变。

- [ ] **Step 2: 跑受影响范围的单测（类名模式，快速回归）**

Run（surefire `-Dtest` 用类名通配，逗号分隔；不支持包级 `**` 通配）：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test \
  -Dtest='ModelProviderService*,ModelConfigService*,ProviderTokenQuota*,ProviderChatModelFactory*' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS。若有其它测试断言旧 `dashscope`/`deepseek` 配额键、或断言 `seedWorkspaceModels` 复制全部 provider，在此暴露——按本计划同样的"改挂默认版 / 过滤白名单"原则修正其期望（仅限期望值，不放宽断言）。

- [ ] **Step 3:（可选，耗时较长）跑整个 mateclaw-server 单测套件做全量回归**

Run（注意：全量套件较慢，文档记载约数十分钟）：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -q -pl mateclaw-server -am test
```

Expected: BUILD SUCCESS。（不含 `*IT`，那些按需单独跑。）

- [ ] **Step 4: 若 Step 2/3 有连带修复，提交**

```bash
git add -A
git commit -m "test(llm): 对齐受默认版配额改挂/种子白名单影响的既有测试期望

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 范围外与已知边界

- **管理员把默认工作区(id=1)默认 chat 模型改到非白名单 provider**：本次不处理。新用户种子化依赖"默认工作区默认 chat 模型保持在白名单 provider（`dashscope-default`）内"这一前提（经 V145 迁移成立）。若管理员手动把 id=1 默认模型改到被剔除的 provider，新用户复制后将缺默认 chat 模型——属 spec §4.2 接受的边界，不在本计划范围。
- **老用户首次用默认版懒创建 -default 配额行**：`recordUsage`/`getQuota` 在 quota 行不存在时按 2M/3M 懒创建，对非默认工作区的老用户同样生效（spec §4.3 已接受）。已由 Task 1 的 `recordUsageLazilyCreatesDefaultVersionQuotaRow` 单测覆盖。
- 清理 V135 遗留的 `dashscope`/`deepseek` 惰性配额行；配额充值/运行期可配限额；老用户 provider 列表回溯清理——均不在本次范围。

## 验收清单（实现完成后逐条核对）

- [ ] 新注册工作区「云端模型」只含 `dashscope-default` + `deepseek-default`（IT: `cloudProvidersAreOnlyTheTwoManagedDefaults`）。
- [ ] 本地 provider（Ollama/LM Studio/llama.cpp/MLX）仍保留（IT: `localProvidersArePreserved`）。
- [ ] 两个默认版 provider 已 `enabled=true` 且 api_key = 配置文件注入值（单测: `seedWorkspaceModelsFiltersToManagedDefaultsAndLocalProviders` + 既有 `DefaultCloudProviderSeedIT`）。
- [ ] `dashscope-default` 限额 200 万、`deepseek-default` 限额 300 万且真正拦截（单测: `assertNotExhausted...` + IT: `quotaRowsSeededWithRequestedLimits`）。
- [ ] 管理员工作区(id=1)豁免配额（单测 + IT）。
- [ ] 无孤儿模型、默认聊天模型与默认 embedding 模型均在 dashscope-default（IT）。
- [ ] 默认工作区模板、种子数据、key 注入机制均未改；无新增 DB 迁移。
- [ ] 既有 `DefaultCloudProviderSeedIT` 与 llm 单测套件全绿。
