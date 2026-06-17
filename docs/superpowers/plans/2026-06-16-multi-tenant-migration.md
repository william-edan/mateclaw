# mateclaw 多租户改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 mateclaw 从「基础多工作区（约 70%）」推进到「跨工作区默认安全」——先堵死已确认的越权破口，再补齐注册开通的功能完整性与物理隔离，最后用 MyBatis-Plus 租户拦截器根治「手写易漏」。

**Architecture:** 隔离当前 100% 靠每条 SQL 手写 `.eq(workspaceId)` + opt-in 注解守卫（`@RequireWorkspaceRole` / `@RequireGlobalAdmin`，由 `WorkspaceAccessInterceptor` 强制）。`WorkspaceContextHolder`（ThreadLocal）+ HTTP/@Async 上下文传播地基已就位；本计划补齐 off-request 路径绑定与 reactive 传播，并最终引入 `TenantLineInnerInterceptor` 兜底。所有资源归属校验统一为 **fail-closed**（取不到归属一律拒绝，绝不回落 workspace=1）。

**Tech Stack:** Java 21（**必须 JDK 21 构建**）、Spring Boot、MyBatis-Plus、Reactor、JUnit5 + Mockito + MockMvc、Flyway 迁移（h2 + mysql 双方言）。

---

## 0. 构建与测试约定（每个 task 适用）

```bash
# 必须用 JDK 21（默认 mvn 走 JDK 23 会让 Lombok 静默失效报"找不到符号"）
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home

# 跑单个测试类（多模块，server 依赖 plugin-api，必须 -am）
mvn -q -pl mateclaw-server -am test -Dtest=类名 -Dsurefire.failIfNoSpecifiedTests=false
```

- `-q` 会吞输出，结果读 `mateclaw-server/target/surefire-reports/<全限定类名>.txt`。
- 工作树有约 28 个**预存失败测试**（ProviderInitProbe、BrowserPairing、SkillBundleMaterializer、onboardingBrand i18n 等），与本计划无关；用 `-Dtest=类名` 精确跑可避开。
- 数据库迁移双方言：`mateclaw-server/src/main/resources/db/migration/h2/` 与 `.../mysql/`。新增迁移**两套都加**，文件名 `V<下一序号>__描述.sql`；先 `ls 目录 | sort -V | tail` 找真实最大序号（注意 V100+ 已存在）。

## 1. 现状（约完成 70%）

- ✅ 注册自动建 workspace + owner 成员；角色/Capability 模型；前端切换器/RBAC 守卫。
- ✅ `WorkspaceContextHolder`（`callWith`/`runWith`/`get`/`set`/`clear`）。
- ✅ HTTP 入口绑定 [WorkspaceContextInterceptor](../../../mateclaw-server/src/main/java/vip/mate/config/WorkspaceContextInterceptor.java)；`@Async` 经 [WorkspaceContextTaskDecorator](../../../mateclaw-server/src/main/java/vip/mate/workspace/core/WorkspaceContextTaskDecorator.java) 挂在 [AsyncSecurityConfig](../../../mateclaw-server/src/main/java/vip/mate/config/AsyncSecurityConfig.java) 的虚拟线程 executor 上。
- ✅ [ModelWorkspaceResolver.currentWorkspaceId()](../../../mateclaw-server/src/main/java/vip/mate/llm/service/ModelWorkspaceResolver.java) = holder → header → 默认 1L。
- ✅ 前一轮 P0/P1 越权修复 + 全局配置收紧到 `@RequireGlobalAdmin`（已提交）。
- ❌ 仍无 `TenantLineInnerInterceptor`（[MateClawApplication](../../../mateclaw-server/src/main/java/vip/mate/MateClawApplication.java) 只有分页拦截器）。
- ❌ 仍有 6 处已确认越权破口；off-request 路径未绑上下文；注册不建默认 Agent / basePath 物理目录；物理目录全局共享。

---

## 2. 执行顺序与依赖（关键 —— 阶段编号 ≠ 执行顺序）

整个改造分 **5 个部分**，按下面顺序执行（这是对抗式审查后修正的顺序，解决了「P0 实际依赖某个 P1 地基」的矛盾）：

| 顺序 | 部分 | 内容 | 依赖 |
|---|---|---|---|
| **第 1 部分** | 地基 | off-request 工作区上下文绑定 + fail-closed | 无（是后续多项的前置）|
| **第 2 部分** | P0 越权修复 | 公共 `AgentWorkspaceVerifier` + 6 项破口 | skill-secret 项依赖**第 1 部分**；planning/memory 项依赖 §2.0 公共组件 |
| **第 3 部分** | P1 开通完整性 | 默认 Agent、basePath 物理目录 | 无 |
| **第 4 部分** | P2 隔离纵深 | 物理目录按工作区隔离、DB 补 `workspace_id` 列 | 无（与第 5 部分配套）|
| **第 5 部分** | P3 架构根治 | `TenantLineInnerInterceptor`、reactive 传播 | **强依赖**第 1 部分（off-request holder 必须先绑好，否则 fail-closed 误杀 cron/workflow）+ 第 4 部分（灰度表先有列）|

**为什么 off-request 地基排第 1**：① 它本身是安全修复——off-request 时 workspace 错误回落 1 = 串户；② skill-secret 经 Agent 工具路径（off-request）解密，其归属校验依赖 holder 已绑；③ 第 5 部分租户插件的 `getTenantId()` fail-closed 依赖所有后台路径已绑 holder，否则正常 cron/workflow 会被异常打挂。

---

## 第 1 部分 · 地基：off-request 工作区上下文绑定 + fail-closed

> ✅ **本部分已完成**（2026-06-16，TDD 红→绿，3 个任务各自提交）：Task 1.1 `8927da46`、Task 1.3 `6a0dd5f1`、Task 1.2 `4e52e049`。5 个新测试全绿，无回归。

修三处 off-request 破口：① cron 执行前不绑 holder；② workflow.v2 无 workspaceId 时硬编码回落 1L；③ `cronDeliveryExecutor` 线程池缺 task decorator。

### Task 1.1: CronJobRunner.executeJob 绑定工作区上下文

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/cron/service/CronJobRunner.java`（`executeJob(CronJobEntity, String)`，约 78-176 行）
- Test: `mateclaw-server/src/test/java/vip/mate/cron/service/CronJobRunnerWorkspaceContextTest.java`（新建）

- [ ] **Step 1: 写失败测试**

`executeJob` 在 null 检查后、`wiki_process` 分支前应把 holder 绑到 `job.getWorkspaceId()`。用 `reminder` 任务类型短路（不触发 LLM），在 `lifecycle.startRun` 的 stub 里捕获当时的 holder 值。

```java
package vip.mate.cron.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import vip.mate.agent.AgentService;
import vip.mate.cron.CronChatOriginFactory;
import vip.mate.cron.CronConversationResolver;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.wiki.service.WikiProcessingService;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CronJobRunnerWorkspaceContextTest {

    @Mock CronJobLifecycleService lifecycle;
    @Mock AgentService agentService;
    @Mock CronChatOriginFactory originFactory;
    @Mock CronConversationResolver conversationResolver;
    @Mock WikiProcessingService wikiProcessingService;

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void executeJob_bindsWorkspaceContextDuringRun_andRestoresAfter() {
        CronJobRunner runner = new CronJobRunner(lifecycle, agentService, originFactory,
                conversationResolver, wikiProcessingService, new ObjectMapper());

        CronJobEntity job = new CronJobEntity();
        job.setId(1L);
        job.setWorkspaceId(7L);
        job.setTaskType("reminder");
        job.setTriggerMessage("hello");

        AtomicReference<Long> seenInsideRun = new AtomicReference<>();
        when(conversationResolver.resolve(job)).thenReturn("conv-1");
        when(lifecycle.startRun(eq(job), any(), any(), eq("conv-1"))).thenAnswer(inv -> {
            seenInsideRun.set(WorkspaceContextHolder.get());
            return new CronJobRunEntity();
        });

        runner.executeJob(job);

        assertThat(seenInsideRun.get()).isEqualTo(7L);
        assertThat(WorkspaceContextHolder.get()).isNull(); // runWith 恢复前值（null）
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home && mvn -q -pl mateclaw-server -am test -Dtest=CronJobRunnerWorkspaceContextTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `seenInsideRun` 为 null（执行期间未绑 holder）。读 `target/surefire-reports/vip.mate.cron.service.CronJobRunnerWorkspaceContextTest.txt`。

- [ ] **Step 3: 最小实现 —— null 检查后用 runWith 包裹**

把 `executeJob(CronJobEntity job, String triggerType)` 在 null 检查之后的全部逻辑抽到私有 `doExecuteJob`，外层用 `WorkspaceContextHolder.runWith` 包裹（导入 `vip.mate.workspace.core.WorkspaceContextHolder`）：

```java
public void executeJob(CronJobEntity job, String triggerType) {
    if (job == null) {
        log.warn("[CronRunner] executeJob called with null job — ignoring");
        return;
    }
    // off-request 路径：把 workspace 显式绑到执行线程，供下游 DB/工具/记忆解析归属。
    WorkspaceContextHolder.runWith(job.getWorkspaceId(), () -> doExecuteJob(job, triggerType));
}

private void doExecuteJob(CronJobEntity job, String triggerType) {
    // ……原 executeJob 从 wiki_process 分支到方法结尾的全部逻辑原样移入……
}
```

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/cron/service/CronJobRunner.java mateclaw-server/src/test/java/vip/mate/cron/service/CronJobRunnerWorkspaceContextTest.java
git commit -m "fix(cron): executeJob 执行期间绑定工作区上下文(off-request)"
```

### Task 1.2: DefaultWorkflowRuntimeV2.resolveWorkspaceId 改 fail-closed

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/workflow/v2/DefaultWorkflowRuntimeV2.java:92-95`
- Test: `mateclaw-server/src/test/java/vip/mate/workflow/v2/DefaultWorkflowRuntimeV2WorkspaceTest.java`（新建）

- [ ] **Step 1: 写失败测试**（先把 `resolveWorkspaceId` 由 `private static` 改为 `static`（包级）以便同包测试调用）

```java
package vip.mate.workflow.v2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultWorkflowRuntimeV2WorkspaceTest {

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void usesInputWorkspaceIdWhenPresent() {
        assertEquals(5L, DefaultWorkflowRuntimeV2.resolveWorkspaceId(Map.of("workspaceId", 5L)));
    }

    @Test
    void fallsBackToHolderWhenInputMissing() {
        WorkspaceContextHolder.runWith(9L, () ->
                assertEquals(9L, DefaultWorkflowRuntimeV2.resolveWorkspaceId(Map.of())));
    }

    @Test
    void failsClosedWhenBothMissing() {
        WorkspaceContextHolder.clear();
        assertThrows(IllegalStateException.class,
                () -> DefaultWorkflowRuntimeV2.resolveWorkspaceId(Map.of()));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `export JAVA_HOME=... && mvn -q -pl mateclaw-server -am test -Dtest=DefaultWorkflowRuntimeV2WorkspaceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `fallsBackToHolder` 与 `failsClosed` 失败（当前无 holder 时返回 1L，不抛异常）。

- [ ] **Step 3: 最小实现**

```java
static Long resolveWorkspaceId(Map<String, Object> input) {
    Long value = resolveLong(input.get("workspaceId"));
    if (value != null) {
        return value;
    }
    Long bound = WorkspaceContextHolder.get();
    if (bound != null) {
        return bound;
    }
    // fail-closed：绝不静默回落 workspace 1，否则脱离上下文的 workflow 会串户
    throw new IllegalStateException(
            "workflow.v2 start 需要 workspaceId：input 与 WorkspaceContextHolder 均为空，拒绝默认到 workspace 1");
}
```

（导入 `vip.mate.workspace.core.WorkspaceContextHolder`。）

- [ ] **Step 4: 运行确认通过** Run 同 Step 2；Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/workflow/v2/DefaultWorkflowRuntimeV2.java mateclaw-server/src/test/java/vip/mate/workflow/v2/DefaultWorkflowRuntimeV2WorkspaceTest.java
git commit -m "fix(workflow): resolveWorkspaceId 优先 holder 并 fail-closed 不回落 1"
```

> 开放点：确认 `start()` 的所有调用方都有合法 workspaceId（input 或 holder）。若存在「系统级无工作区 workflow」的合法场景，应改用专用哨兵而非一律抛异常——执行前 grep 调用方确认。

### Task 1.3: cronDeliveryExecutor 线程池挂 WorkspaceContextTaskDecorator

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/cron/delivery/CronDeliveryListener.java:88-110`（`cronDeliveryExecutor()` bean）
- Test: `mateclaw-server/src/test/java/vip/mate/cron/delivery/CronDeliveryExecutorWorkspacePropagationTest.java`（新建）

> 注意：`cronDeliveryExecutor` 是 `CronDeliveryListener` 里的 `ThreadPoolTaskExecutor` bean（**不在** AsyncSecurityConfig）。AFTER_COMMIT 投递监听器在 executeJob 线程上触发，配合 Task 1.1 的 `runWith`，提交瞬间 holder 已绑，decorator 即可捕获并带到投递线程。

- [ ] **Step 1: 写失败测试**（参照 `AsyncSecurityConfigWorkspacePropagationTest` 的传播校验风格）

```java
package vip.mate.cron.delivery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import vip.mate.audit.service.AuditEventService;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CronDeliveryExecutorWorkspacePropagationTest {

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void cronDeliveryExecutorCarriesWorkspaceToDeliveryThread() throws Exception {
        CronDeliveryListener listener = new CronDeliveryListener(List.of(), mock(AuditEventService.class));
        ThreadPoolTaskExecutor ex = listener.cronDeliveryExecutor();
        CompletableFuture<Long> observed = new CompletableFuture<>();

        WorkspaceContextHolder.callWith(42L, () -> {
            ex.execute(() -> observed.complete(WorkspaceContextHolder.get()));
            return null;
        });

        assertEquals(42L, observed.get(5, TimeUnit.SECONDS),
                "cron 投递任务必须观察到提交时绑定的工作区");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `export JAVA_HOME=... && mvn -q -pl mateclaw-server -am test -Dtest=CronDeliveryExecutorWorkspacePropagationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 投递线程读到 null（线程池未带 decorator）。

- [ ] **Step 3: 最小实现 —— `ex.initialize()` 前挂 decorator**

```java
ex.setThreadNamePrefix("cron-delivery-");
ex.setTaskDecorator(new vip.mate.workspace.core.WorkspaceContextTaskDecorator()); // 携带提交线程的 workspace
ex.setRejectedExecutionHandler((r, executor) -> {
    // ……原有 AbortPolicy + audit 不变……
});
ex.initialize();
```

- [ ] **Step 4: 运行确认通过** Run 同 Step 2；Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/cron/delivery/CronDeliveryListener.java mateclaw-server/src/test/java/vip/mate/cron/delivery/CronDeliveryExecutorWorkspacePropagationTest.java
git commit -m "fix(cron): cronDeliveryExecutor 挂 WorkspaceContextTaskDecorator 传播工作区"
```

---

## 第 2 部分 · P0 跨工作区越权修复

> ✅ **本部分已完成**（2026-06-17，TDD，36 个新增/扩展测试全绿，无回归）。提交：
> §2.0 `895ea8c9`、§2.4 `86802052`、§2.3 `4024b632`、§2.6 `0bf6756a`、§2.1 `c38a8dc2`、§2.5 `f06e54af`、§2.2 `2ebb601d`。
> **两处有意保留的残留**（不阻断本部分，已在对应任务说明）：
> - §2.1 skill-secret 采用 **enforce-when-bound**：holder 未绑的 reactive agent 路径暂不强制（避免误伤非 ws1 合法技能），待**第 5 部分**的 reactive 上下文传播落地后自动生效。
> - §2.2 Wiki 的 `pagesByRawId` / `pagesByChunkId`（按 rawId/chunkId，非 kbId）未加守卫，需 raw/chunk→KB 二跳解析，列为后续。

> 先做 §2.0 公共组件，再做 6 项破口。skill-secret（§2.1）需在**第 1 部分**之后合入。

### Task 2.0: 公共 AgentWorkspaceVerifier（消除三处重复）

当前 `agentId → agent.workspaceId` 归属校验在 [PlanningService.verifyAgentWorkspace](../../../mateclaw-server/src/main/java/vip/mate/planning/service/PlanningService.java)（216 行）与 [AgentBindingController.verifyAgentWorkspace](../../../mateclaw-server/src/main/java/vip/mate/agent/binding/controller/AgentBindingController.java)（43 行）各写一份，§2.3 memory 又要再写一份。抽公共组件统一 fail-closed 语义。

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/workspace/core/security/AgentWorkspaceVerifier.java`
- Test: `mateclaw-server/src/test/java/vip/mate/workspace/core/security/AgentWorkspaceVerifierTest.java`

- [ ] **Step 1: READ** [AgentService.getAgent(Long)](../../../mateclaw-server/src/main/java/vip/mate/agent/AgentService.java)（102 行，返回 `AgentEntity`，含 `getWorkspaceId()`）、`PlanningService.resolveAgentWorkspace`（223 行）确认现有 null/非数字处理；[AgentControllerWorkspaceIsolationTest](../../../mateclaw-server/src/test/java/vip/mate/agent/controller/AgentControllerWorkspaceIsolationTest.java) 抄测试风格。

- [ ] **Step 2: 写失败测试**

```java
package vip.mate.workspace.core.security;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

class AgentWorkspaceVerifierTest {

    private final AgentService agentService = Mockito.mock(AgentService.class);
    private final AgentWorkspaceVerifier verifier = new AgentWorkspaceVerifier(agentService);

    private AgentEntity agent(long id, Long ws) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setWorkspaceId(ws);
        return a;
    }

    @Test
    void passesWhenAgentInWorkspace() {
        when(agentService.getAgent(10L)).thenReturn(agent(10L, 1L));
        assertThatCode(() -> verifier.verify(10L, 1L)).doesNotThrowAnyException();
    }

    @Test
    void rejectsCrossWorkspaceAgent() {
        when(agentService.getAgent(10L)).thenReturn(agent(10L, 2L));
        assertThatThrownBy(() -> verifier.verify(10L, 1L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsMissingAgent_failClosed() {
        when(agentService.getAgent(999L)).thenReturn(null);
        assertThatThrownBy(() -> verifier.verify(999L, 1L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsNonNumericStringAgentId_failClosed() {
        assertThatThrownBy(() -> verifier.verify("not-a-number", 1L)).isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 3: 运行确认失败** Run -Dtest=AgentWorkspaceVerifierTest；Expected: FAIL（类不存在，编译失败）

- [ ] **Step 4: 实现**

```java
package vip.mate.workspace.core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;

/** 统一的 agentId → 工作区归属校验（fail-closed）。供 planning / memory / skill 等复用。 */
@Component
@RequiredArgsConstructor
public class AgentWorkspaceVerifier {

    private final AgentService agentService;

    public void verify(Long agentId, long workspaceId) {
        AgentEntity agent = agentId == null ? null : agentService.getAgent(agentId);
        if (agent == null || agent.getWorkspaceId() == null
                || agent.getWorkspaceId() != workspaceId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent 不属于当前工作区");
        }
    }

    public void verify(String agentId, long workspaceId) {
        Long parsed;
        try {
            parsed = Long.parseLong(agentId);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agentId 非法");
        }
        verify(parsed, workspaceId);
    }
}
```

- [ ] **Step 5: 运行确认通过** Run 同上；Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add mateclaw-server/src/main/java/vip/mate/workspace/core/security/AgentWorkspaceVerifier.java mateclaw-server/src/test/java/vip/mate/workspace/core/security/AgentWorkspaceVerifierTest.java
git commit -m "feat(workspace): 抽公共 AgentWorkspaceVerifier(fail-closed) 统一 agentId 归属校验"
```

> 后续 §2.3 memory、以及（可选重构）PlanningService/AgentBindingController 均改为注入并调用本组件，删除各自重复实现。

### Task 2.1: 技能密钥经 Agent 工具路径越权解密（依赖第 1 部分）

破口：[SkillScriptTool.runSkillScript](../../../mateclaw-server/src/main/java/vip/mate/tool/builtin/SkillScriptTool.java)（约 104 行）与 [ScriptSkillWrapperToolFactory](../../../mateclaw-server/src/main/java/vip/mate/skill/runtime/ScriptSkillWrapperToolFactory.java)（约 146 行）经 LLM 工具路径直接 `skillSecretService.getDecrypted(skillId)`，无工作区校验。在 [SkillSecretService.getDecrypted](../../../mateclaw-server/src/main/java/vip/mate/skill/secret/SkillSecretService.java)（61-81 行）集中收口。

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/skill/secret/SkillSecretService.java`
- Test: `mateclaw-server/src/test/java/vip/mate/skill/secret/SkillSecretServiceWorkspaceIsolationTest.java`（新建）

- [ ] **Step 1: READ** `SkillSecretService.getDecrypted`；`SkillService.getSkill`（确认返回实体含 `workspaceId`、builtin 判定字段——是 `isBuiltin()` 还是 `workspaceId==null`）；已修的 `SkillSecretController.verifySkillWorkspace` 与 `SkillSecretControllerWorkspaceIsolationTest`（抄逻辑与风格）。
- [ ] **Step 2: 写失败测试**（跨工作区 skill 解密被拒；builtin 放行）

```java
@Test
void getDecrypted_deniesCrossWorkspaceSkill() {
    SkillEntity skill = new SkillEntity();
    skill.setId(50L); skill.setWorkspaceId(2L); skill.setBuiltin(false);
    when(skillService.getSkill(50L)).thenReturn(skill);
    WorkspaceContextHolder.runWith(1L, () ->
        assertThatThrownBy(() -> skillSecretService.getDecrypted(50L))
            .isInstanceOf(ResponseStatusException.class));
}
```

- [ ] **Step 3: 运行确认失败** Run -Dtest=SkillSecretServiceWorkspaceIsolationTest；Expected: FAIL（不抛异常）
- [ ] **Step 4: 实现**（getDecrypted 首部，builtin/workspaceId==null 放行，否则比对 `ModelWorkspaceResolver.currentWorkspaceId()`，不符抛 403）
- [ ] **Step 5: 运行确认通过**
- [ ] **Step 6: 回归 + 提交**

```bash
mvn -q -pl mateclaw-server -am test -Dtest=SkillSecretServiceWorkspaceIsolationTest,SkillSecretControllerWorkspaceIsolationTest -Dsurefire.failIfNoSpecifiedTests=false
git commit -m "fix(skill): SkillSecretService.getDecrypted 收口工作区校验防 Agent 工具路径越权"
```

> 强依赖第 1 部分：Agent 工具路径为 off-request，`currentWorkspaceId()` 靠 holder；holder 未绑则校验失效或误判。执行前确认所有解密入口都过 `getDecrypted`（如有 `getDecryptedByKey` 等需一并收口）。

### Task 2.2: Wiki 整类越权（4 个 controller + 共享 verifyKbWorkspace）

破口：[WikiRelationController](../../../mateclaw-server/src/main/java/vip/mate/wiki/controller/WikiRelationController.java)（6 端点全无注解）、[WikiHotCacheController](../../../mateclaw-server/src/main/java/vip/mate/wiki/controller/WikiHotCacheController.java)（无注解）；[WikiResearchController](../../../mateclaw-server/src/main/java/vip/mate/wiki/controller/WikiResearchController.java) / [WikiAdminController](../../../mateclaw-server/src/main/java/vip/mate/wiki/controller/WikiAdminController.java) 有角色注解但缺 kbId 归属校验。`WikiKnowledgeBaseEntity` 有 `workspaceId`，子实体（Chunk/Page/Relation/RawMaterial）只有 `kbId` → 校验路径 `kbId → KB.workspaceId`。

**Task A — 共享 verifyKbWorkspace**（放 `WikiKnowledgeBaseService`，fail-closed，公共/builtin KB 若存在 `workspaceId==null` 则放行）→ 测试 `WikiKbWorkspaceVerifyTest`。
**Task B — WikiRelationController**：6 端点（search-preview/related/citations/stats/enrich/repair）各加 `@RequireWorkspaceRole`（读 viewer / 写 enrich·repair member）+ 首行 `verifyKbWorkspace(kbId)` → 测试 `WikiRelationControllerWorkspaceIsolationTest`。
**Task C — WikiHotCacheController**：get(viewer)/regenerate·reset(member) + `verifyKbWorkspace` → 测试 `WikiHotCacheControllerWorkspaceIsolationTest`。
**Task D — WikiResearch/Admin**：两方法首行补 `verifyKbWorkspace(kbId)` → 测试 `WikiResearchAdminWorkspaceIsolationTest`。

每个 Task 走完整 TDD（失败测试 → 跑 → 实现 → 跑 → 提交）。示例（Task A 实现）：

```java
public void verifyKbWorkspace(Long kbId) {
    if (kbId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "kbId 缺失");
    WikiKnowledgeBaseEntity kb = this.getById(kbId);
    if (kb == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "知识库不存在");
    if (kb.getWorkspaceId() != null
            && !kb.getWorkspaceId().equals(ModelWorkspaceResolver.currentWorkspaceId())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "知识库不属于当前工作区");
    }
}
```

> 执行前确认：① 各 controller 的 kbId 是路径变量还是查询参数；② 是否存在 `workspaceId==null` 的公共 KB（决定是否放行）；③ wiki 读端点最低角色约定。

### Task 2.3: 私有记忆越权（Memory/Fact 按 agentId 归属校验）

破口：[MemoryController](../../../mateclaw-server/src/main/java/vip/mate/memory/controller/MemoryController.java) / [FactController](../../../mateclaw-server/src/main/java/vip/mate/memory/fact/controller/FactController.java) 有 `@RequireWorkspaceRole("member")` 但不校 agentId 归属。

**Files:** 修改两 controller；**复用 §2.0 的 `AgentWorkspaceVerifier`**（注入后每个按 agentId 端点首行 `agentWorkspaceVerifier.verify(agentId, ModelWorkspaceResolver.currentWorkspaceId())`）。Test: `MemoryControllerWorkspaceIsolationTest`（跨 ws agentId / 不存在 agentId 均 403）。完整 TDD。

> 执行前确认：是否有按 `factId` 直接操作的端点（需 `fact→agentId→workspace` 二跳）。

### Task 2.4: Planning 全局越权 + 短路放行

破口：`PlanningService.findAwaitingApprovalContext()`（174-205）全局取最近 running plan 不校归属；`verifyAgentWorkspace`（216-235）在 `resolveAgentWorkspace` 返回 null 时短路放行。

**Files:** 修改 `PlanningService`；**改用 §2.0 `AgentWorkspaceVerifier`** 替换内部 `verifyAgentWorkspace`（删重复实现）。Test: 扩展 [PlanningServiceWorkspaceIsolationTest](../../../mateclaw-server/src/test/java/vip/mate/planning/PlanningServiceWorkspaceIsolationTest.java)——① `findAwaitingApprovalContext` 跳过他 ws 的 plan（返回 null）；② agentId 非数字/不存在时抛异常。完整 TDD。`findAwaitingApprovalContext` 改为按 `ModelWorkspaceResolver.currentWorkspaceId()` 经 `agentId→workspace` 过滤。

### Task 2.5: 工具审批列表越权

破口：[SecurityController.listApprovals](../../../mateclaw-server/src/main/java/vip/mate/tool/guard/controller/SecurityController.java)（225-236）传 conversationId 不校归属、不传时 `listPendingFromDb` 返回全局 PENDING。

**Files:** 修改 `SecurityController.listApprovals` + `ApprovalWorkflowService`（新增 `listPendingByWorkspace`）。Test: `SecurityControllerApprovalIsolationTest`——① 传他 ws conversationId 返回 403；② 全局 list 仅返回当前 ws。完整 TDD。

> 执行前 READ `ConversationService` 确认是否已有 `getWorkspaceId(conversationId)` / `listConversationIdsByWorkspace`（无则新增）。第 4 部分给 `tool_approval` 补 `workspace_id` 后，可把 `IN(conversationIds)` 简化为 `.eq(workspaceId)`。

### Task 2.6: MCP 全局凭证收口为 GlobalAdmin

破口：[McpServerController](../../../mateclaw-server/src/main/java/vip/mate/tool/mcp/controller/McpServerController.java) 写端点用 `@RequireWorkspaceRole("admin")`，而 `mcp_server` 无 `workspace_id`（全局表）→ 任何工作区 owner 可改全局凭证。

**Files:** 把所有写端点（create/update/delete/enable/disable/updateConfig 等真实方法名据 READ 确认）改为 `@RequireGlobalAdmin`。Test: `McpServerControllerGlobalAdminTest`（反射断言写方法标了 `@RequireGlobalAdmin`，仿 `GlobalConfigRequiresGlobalAdminTest`）。完整 TDD。

> 待决策：读端点是否也收口；是否真正给 `mcp_server` 分租户（补 `workspace_id`，更大改动，单独立项）。

---

## 第 3 部分 · P1 注册开通完整性

> 🟡 **部分完成**（2026-06-17，TDD，3 新增测试 + 既有回归全绿）：
> - ✅ **3.1 默认 Agent**：`WorkspaceService.seedDefaultAgent`（幂等，无 agent 时建 react/enabled 默认 agent）。
> - ✅ **3.2a basePath 目录创建**：`seedBasePath` 按 `{mateclaw.workspace.base-root:~/.huafanai/workspaces}/{id}` 建目录并回填（仅新工作区）。提交于 Part 3 单次提交。
> - ⏸ **3.2b PathGuard fail-closed + 存量 basePath 回填**：**延后（部署决策）**——把 [WorkspacePathGuard:55](../../../mateclaw-server/src/main/java/vip/mate/tool/guard/WorkspacePathGuard.java) 的「空 basePath → 不限制」改 fail-closed 会**突然限制/打断存量 null-basePath 工作区**的 file-tool；需先回填存量工作区 basePath（且确认 basePath 可靠流入 ChatOrigin），再灰度切换。

### Task 3.1: 注册创建默认 Agent

新工作区无 agent → Chat/工具/automation/memory/cron/trigger 全废。[WorkspaceService.create](../../../mateclaw-server/src/main/java/vip/mate/workspace/core/service/WorkspaceService.java) 在 `seedModelConfiguration` 之后补 `seedDefaultAgent(workspaceId)`（幂等：已有 agent 则跳过）。Test: `WorkspaceServiceSeedAgentTest`（create 后该 ws 至少 1 个 agent 且 workspaceId 正确）。完整 TDD。

> 执行前 READ ws=1 默认 agent 或 `TemplateService` 确认必填字段/默认值；**优先复用** `TemplateService`/`AgentService.createAgent` 而非裸 insert（保证关联资源一并建立）。

### Task 3.2: basePath 物理目录创建 + PathGuard fail-closed

`WorkspaceService.create` 只入库 basePath 不建目录；[WorkspacePathGuard](../../../mateclaw-server/src/main/java/vip/mate/tool/guard/WorkspacePathGuard.java)（54-56）basePath 为 null 时放行任意路径。

**Files:** create 时算 basePath（`{根}/{workspaceId}`）+ `Files.createDirectories` + 回填（依赖自增 id，insert 后 update）；`WorkspacePathGuard` 对 null basePath 改 fail-closed。Test: `WorkspaceBasePathSeedTest` + 扩展 `WorkspacePathGuardShellTest`。完整 TDD。

> ⚠️ **分两次发布**：fail-closed 会让存量 `basePath=null` 工作区 file-tool 全挂。顺序：(1) 先上建目录逻辑；(2) 写一次性迁移给存量 ws 回填 basePath 并建目录；(3) 确认无 null 后再上 fail-closed。basePath 根目录建议走配置项而非硬编码 user.home。

---

## 第 4 部分 · P2 隔离纵深

> 🟡 **状态（2026-06-17）**：
> - ✅ **4.2 DB 补列**：8 张表（wiki_chunk/page/relation/raw_material ← kb；fact/memory_recall/dream_report ← agent；tool_approval ← conversation）补 nullable `workspace_id` + 双方言回填。V143 迁移，嵌入式 H2 跑真实文件验证（列+三种回填）。提交 `ea15a7d2`。实体字段 + 防御纵深表（skill_file/workflow_revision/workflow_run_step/agent_pause，部分多跳）留待 Part 5（租户插件 SQL 层不需实体字段）。
> - **4.1 物理目录隔离 — 评估后分流**：
>   - **plugin 目录**：**不改**。PluginManager 启动时把 JAR 载入 JVM 类加载器 = 实例级能力；工作区插件已支持在 `basePath/plugins`。全局 `~/.huafanai/plugins` 有意为实例级。（解掉"plugin 工作区级 vs 实例级"决策。）
>   - **skill 目录**：**并入 Part 5**。路径解析靠 `currentWorkspaceId()`，但技能执行走 reactive agent 路径、context 常未绑（与 §2.1 同一耦合）→ 现在按工作区分目录会让非 ws1 技能在 reactive 路径解析到错目录。待 Part 5 reactive 上下文落地后一起做。
>   - **wiki 上传目录**：REST 路径 context 已绑、可单独做，但面窄 + 有存量文件迁移问题，低优先。

### Task 4.1: 物理目录按工作区隔离

[SkillWorkspaceProperties](../../../mateclaw-server/src/main/java/vip/mate/skill/workspace/SkillWorkspaceProperties.java)（22，`~/.huafanai/skills`）、[PluginProperties](../../../mateclaw-server/src/main/java/vip/mate/plugin/PluginProperties.java)（21，`~/.huafanai/plugins`）、[WikiProperties](../../../mateclaw-server/src/main/java/vip/mate/wiki/WikiProperties.java)（66，`./data/wiki-uploads`）全局共享 → 改 `{root}/{workspaceId}/`。拆 3 个 Task（skill/plugin/wiki 各一）。测试断言不同 ws 解析出的目录**不同且互不包含**。

> 待决策：① **plugin 是工作区级还是实例级？**（实例级则不分租户、只收口管理端点 global-admin）；② 存量全局目录数据需一次性迁到 ws=1 子目录；③ builtin skill 目录保持全局（workspaceId=null）；④ wiki 上传若已落库文件路径，改结构需同步迁存量路径。

### Task 4.2: DB 补 workspace_id 列（防御纵深，为第 5 部分铺路）

给仅间接隔离的表补 `workspace_id` 并回填：wiki 子表（经 `kb_id→kb.workspace_id`）、memory 三表（经 `agent_id→agent.workspace_id`）、tool_approval（经 conversation）、防御纵深表 skill_file/workflow_revision/workflow_run_step/agent_pause。h2 + mysql 双方言迁移 + Entity 加字段。Test: `WorkspaceIdBackfillMigrationTest`（列存在 + 回填值正确，仿 `WorkspaceSchemaMigrationTest`）。

> ⚠️ **不可省略**：迁移 SQL 必须**逐表写全** ALTER + 回填 UPDATE（h2 用相关子查询、mysql 用 JOIN UPDATE），不能 `-- 其余表同模式` 占位。
> 待决策：① 多跳回填表（如 workflow_run_step 经 run_id→run→workspace）确认中间表有列；② 本阶段列留 nullable，NOT NULL 留到第 5 部分逐表确认后；③ **新写入路径补值**——补列只回填历史，各 service 的 insert 路径需 set workspaceId（随对应改造或第 5 部分处理）。

---

## 第 5 部分 · P3 架构根治

> 🟢 **机制已就位并测试，开启=部署灰度**（2026-06-17，配置开关默认关 → 生产零变化）：
> - ✅ **5.1 TenantLineInnerInterceptor**：`WorkspaceTenantLineHandler`（`getTenantIdColumn=workspace_id`，`getTenantId` 读 holder **fail-closed**，`ignoreTable` 走构造器注入的灰度白名单）+ `MateClawApplication` 用 `mateclaw.tenant.line-interceptor-enabled`(默认 false) + `-tables`(逗号白名单) 条件注册（在分页前）。提交 `8ab60bc3`。
> - ✅ **5.2 reactive 上下文传播**：`WorkspaceThreadLocalAccessor`（Micrometer context-propagation 桥接）+ `ReactiveWorkspaceContextConfig` 用 `mateclaw.tenant.reactive-context-propagation-enabled`(默认 false) 注册 accessor + `Hooks.enableAutomaticContextPropagation()`。测试证明 workspaceId 跨 `publishOn` 存活。提交 `fe50a176`。
> - **开启即灰度（运维/部署动作，非代码）**：① 先开 reactive 传播开关（闭合 §2.1、解锁 skill 目录）；② 再逐表把 `workspace_id` 表加进 `-tables` 白名单并开 line-interceptor 开关，每加一张跑全量回归；③ getTenantId 是 fail-closed，开某表前其所有读写路径上下文必须已绑（Part 1 已覆盖 off-request，reactive 由 5.2 开关覆盖）。INSERT 注入会盖 seed/copy 的目标 ws，启用此类表需评估。

### Task 5.1: 挂 TenantLineInnerInterceptor（影子 + 逐表灰度）

[MateClawApplication](../../../mateclaw-server/src/main/java/vip/mate/MateClawApplication.java)（48-53）只有分页拦截器。新建 `WorkspaceTenantLineHandler`：`getTenantIdColumn()="workspace_id"`、`getTenantId()` 读 holder（**取不到 fail-closed 抛异常，绝不回落 1**）、`ignoreTable()` 用**白名单灰度**（仅 `ENABLED_TABLES` 内的表注入条件，其余全 ignore）。注册在分页拦截器**之前**。建议 INSERT 一律 ignore。Test: `WorkspaceTenantLineHandlerTest`（holder 有值→注入；无值→抛异常；豁免表→不注入；列名=workspace_id）。

> **强依赖第 1 部分 + reactive（§5.2）**：fail-closed 前所有 off-request 路径必须已绑 holder，否则正常 cron/workflow/async 被 `IllegalStateException` 打挂。建议灰度初期 `getTenantId()` 无上下文时**先告警不抛**，稳定后切 fail-closed。
> 豁免清单（必入 ignoreTable）：builtin skill（workspace_id=null）、findModelByIdAnyWorkspace、ProviderInitProbe 全量扫、wiki 公共 KB、所有 seed/copy 写目标 ws、全局表（datasource/mcp_server/hook/personal_access_token/system_setting/feature_flag 等）。逐表加入 `ENABLED_TABLES`，每加一张跑全量回归。先确认 MyBatis-Plus 版本的 INSERT 忽略 API。

### Task 5.2: Reactive 路径工作区上下文传播

agent 是 reactive Flux，靠 [ChatOriginHolder](../../../mateclaw-server/src/main/java/vip/mate/agent/context/ChatOriginHolder.java)（ThreadLocal）传 ChatOrigin，跨 scheduler 丢上下文。方案：入口 `contextWrite` 写 workspaceId 进 Reactor Context，消费处 `deferContextual` 读出并在受限作用域 `callWith` 绑 holder。Test: `ReactiveWorkspaceContextTest`（Flux 跨 `publishOn` 后仍读到正确 workspaceId）。

> 探索性项，建议先 spike：评估 Micrometer `context-propagation` + `Hooks.enableAutomaticContextPropagation`（Reactor 3.5+）自动传播 ThreadLocal，比手写桥接更彻底（需引依赖+确认版本）。全链路梳理 reactive 中所有读 workspace 的点（不止 chatStream）。

---

## 3. 待用户决策项汇总

1. **是否现在上 TenantLineInnerInterceptor（第 5 部分）**：强烈建议——否则每加表/端点都靠人记得手写 `.eq`，串户是时间问题。需灰度。
2. **敏感全局表分租户 vs 收口 global-admin**：datasource（连接串）、mcp_server（凭证）、hook/hook_run（可触发跨工作区副作用）。决定是否大改 schema 补 `workspace_id`。
3. **plugin 目录工作区级 vs 实例级**（影响第 4 部分 4.1）。
4. **改造节奏**：P0 优先全堵完（推荐）还是与 P1 并行让新用户尽快可用。

## 4. 自审结论（writing-plans Self-Review）

- **Spec 覆盖**：现状分析的 6 项 P0 破口、3 项 P1 缺口、物理目录/补列、租户插件/reactive 均有对应 Part/Task。✅
- **占位符**：第 1 部分 + §2.0 已写全代码；§2.2–2.6、第 3–5 部分给出 Files+步骤骨架+关键代码，凡需现场确认处显式标「执行前 READ」。第 4.2 已硬性要求迁移 SQL 逐表写全（执行时补全，禁止占位）。
- **符号一致性**：agentId→ws 校验统一收敛到 `AgentWorkspaceVerifier`（§2.0），§2.3/2.4 引用它；`verifyKbWorkspace` 统一放 `WikiKnowledgeBaseService`。✅
- **依赖顺序**：见 §2，已显式说明「阶段编号 ≠ 执行顺序」，off-request 地基前置。✅

## 5. 执行交接

**第 1 部分本次已由主会话直接实现（TDD，测试通过并提交）**，见下文进度。第 2–5 部分两种执行方式：
1. **Subagent-Driven（推荐）**：每个 Task 派新 subagent + 两段式 review，迭代快。
2. **Inline**：本会话内按 executing-plans 批量执行 + 检查点。

---

## 6. 收尾：部署/运维项已落地 + 灰度开关清单（2026-06-17）

「部署/运维决策」1–4 项的**代码已全部落地并测试**；剩下的是按下面顺序在部署时翻开关 + 跑回归。

**已直接生效（安全、默认行为）**：
- **4a** Wiki `pagesByRawId/pagesByChunkId` 补工作区守卫（真·越权修复，已生效）。
- **4b** V144 防御纵深补列（skill_file/workflow_revision/workflow_run_step/agent_pause，下一迁移 V145）。
- **item2 回填** `WorkspaceBasePathBackfillRunner`（@Order 6）启动即给存量工作区补 basePath（幂等、非致命）。

**灰度开关（默认全关 = 生产零变化，按此顺序开）**：

| 顺序 | 开关 | 作用 | 前置/注意 |
|---|---|---|---|
| ① | `mateclaw.tenant.reactive-context-propagation-enabled` | reactive 链传播工作区（闭合 §2.1、解锁 skill 目录） | 全局 Reactor Hook，影响 19 Flux/12 SSE/8 WebClient，单独窗口灰度 + 回归 |
| ② | `mateclaw.skill.per-workspace-dir` | skill 目录按工作区分子目录 | 与①同批；存量非默认工作区技能需迁移目录到 `{root}/{wsId}/` |
| ③ | `mateclaw.workspace.path-guard-fail-closed` | 空 basePath 的文件/Shell 工具改拒绝 | 先确认回填 Runner 已让全量 basePath 非空 |
| ④ | `mateclaw.tenant.line-interceptor-enabled` + `-tables` | 逐表挂租户拦截器 | 无 100% 干净表；研究推荐首表 **mate_datasource**；忌 mate_agent/conversation（全量扫+跨 ws seed）；INSERT 注入会盖 seed/copy 目标 ws |

> 关键：①②③④**串行灰度、不要叠加**，出问题才能二分定位。④的 fail-closed `getTenantId` 依赖所有路径上下文已绑（off-request 由第 1 部分覆盖、reactive 由①覆盖）。
