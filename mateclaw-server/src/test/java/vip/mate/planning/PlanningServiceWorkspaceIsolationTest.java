package vip.mate.planning;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.planning.model.PlanEntity;
import vip.mate.planning.model.SubPlanEntity;
import vip.mate.planning.repository.PlanMapper;
import vip.mate.planning.repository.SubPlanMapper;
import vip.mate.planning.service.PlanningService;
import vip.mate.workspace.core.WorkspaceContextHolder;
import vip.mate.workspace.core.security.AgentWorkspaceVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Workspace isolation for the plan read endpoints. PlanEntity carries no
 * workspace_id column of its own — a plan belongs to the workspace of its
 * owning agent. These tests pin the guard that resolves agent to workspace and
 * rejects reads from a foreign workspace (the PlanningController endpoints
 * previously had no authorization at all).
 */
class PlanningServiceWorkspaceIsolationTest {

    private PlanMapper planMapper;
    private SubPlanMapper subPlanMapper;
    private AgentMapper agentMapper;
    private PlanningService service;

    @BeforeAll
    static void initMyBatisPlusCache() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), PlanEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SubPlanEntity.class);
    }

    @BeforeEach
    void setUp() {
        planMapper = mock(PlanMapper.class);
        subPlanMapper = mock(SubPlanMapper.class);
        agentMapper = mock(AgentMapper.class);
        service = new PlanningService(planMapper, subPlanMapper, new AgentWorkspaceVerifier(agentMapper));
    }

    @Test
    void listPlansByAgentRejectsAgentFromOtherWorkspace() {
        AgentEntity agent = new AgentEntity();
        agent.setId(5L);
        agent.setWorkspaceId(99L);
        when(agentMapper.selectById(5L)).thenReturn(agent);

        assertThrows(MateClawException.class, () -> service.listPlansByAgent("5", 1L),
                "plans of an agent owned by workspace 99 must not be listable from workspace 1");
    }

    @Test
    void getPlanWithStepsRejectsPlanWhoseAgentIsInOtherWorkspace() {
        PlanEntity plan = new PlanEntity();
        plan.setId(10L);
        plan.setAgentId("5");
        when(planMapper.selectById(10L)).thenReturn(plan);
        AgentEntity agent = new AgentEntity();
        agent.setId(5L);
        agent.setWorkspaceId(99L);
        when(agentMapper.selectById(5L)).thenReturn(agent);

        assertThrows(MateClawException.class, () -> service.getPlanWithSteps(10L, 1L),
                "a plan whose agent belongs to workspace 99 must not be readable from workspace 1");
    }

    @Test
    void listPlansByAgentAllowsAgentInOwnWorkspace() {
        AgentEntity agent = new AgentEntity();
        agent.setId(5L);
        agent.setWorkspaceId(1L);
        when(agentMapper.selectById(5L)).thenReturn(agent);
        when(planMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.listPlansByAgent("5", 1L));
    }

    @Test
    void getPlanWithStepsAllowsPlanWhoseAgentIsInOwnWorkspace() {
        PlanEntity plan = new PlanEntity();
        plan.setId(10L);
        plan.setAgentId("5");
        when(planMapper.selectById(10L)).thenReturn(plan);
        AgentEntity agent = new AgentEntity();
        agent.setId(5L);
        agent.setWorkspaceId(1L);
        when(agentMapper.selectById(5L)).thenReturn(agent);
        when(subPlanMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.getPlanWithSteps(10L, 1L));
    }

    /**
     * findAwaitingApprovalContext 此前全局取最近一条 running 计划不校归属。
     * 现在当最近的 running 计划属于别的工作区时,当前工作区必须取不到它。
     */
    @Test
    void findAwaitingApprovalContextSkipsRunningPlanOfOtherWorkspace() {
        PlanEntity foreign = new PlanEntity();
        foreign.setId(10L);
        foreign.setAgentId("5");
        foreign.setStatus("running");
        when(planMapper.selectList(any())).thenReturn(List.of(foreign));
        AgentEntity agent = new AgentEntity();
        agent.setId(5L);
        agent.setWorkspaceId(99L);
        when(agentMapper.selectById(5L)).thenReturn(agent);

        PlanningService.PlanResumeContext ctx =
                WorkspaceContextHolder.callWith(1L, () -> service.findAwaitingApprovalContext());

        assertNull(ctx, "a running plan owned by workspace 99 must not be resumable from workspace 1");
    }

    /** fail-closed: agentId 非数字(resolveAgentWorkspace 旧逻辑会短路放行)现在必须拒绝。 */
    @Test
    void listPlansByAgentRejectsNonNumericAgentId() {
        assertThrows(MateClawException.class, () -> service.listPlansByAgent("not-a-number", 1L));
    }

    /** fail-closed: agent 不存在也必须拒绝,不能回落放行。 */
    @Test
    void listPlansByAgentRejectsNonexistentAgent() {
        when(agentMapper.selectById(404L)).thenReturn(null);
        assertThrows(MateClawException.class, () -> service.listPlansByAgent("404", 1L));
    }
}
