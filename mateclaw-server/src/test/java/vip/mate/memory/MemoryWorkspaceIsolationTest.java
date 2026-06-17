package vip.mate.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.memory.controller.MemoryController;
import vip.mate.memory.fact.controller.FactController;
import vip.mate.workspace.core.WorkspaceContextHolder;
import vip.mate.workspace.core.security.AgentWorkspaceVerifier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Memory / Fact endpoints carry {@code @RequireWorkspaceRole("member")} but
 * historically did not verify that the path {@code agentId} belongs to the
 * caller's workspace — a member could read another workspace's private
 * facts / memory by passing a foreign agentId. These pin the fail-closed
 * {@code agentId → workspace} guard.
 */
class MemoryWorkspaceIsolationTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AgentWorkspaceVerifier verifier = new AgentWorkspaceVerifier(agentMapper);

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    /** agent 5 belongs to workspace 99; caller is in workspace 1. */
    private void foreignAgentBoundToWorkspace1() {
        AgentEntity a = new AgentEntity();
        a.setId(5L);
        a.setWorkspaceId(99L);
        when(agentMapper.selectById(5L)).thenReturn(a);
        WorkspaceContextHolder.set(1L);
    }

    @Test
    void memoryEmergenceRejectsCrossWorkspaceAgent() {
        foreignAgentBoundToWorkspace1();
        MemoryController controller = new MemoryController(
                mock(vip.mate.memory.service.MemoryEmergenceService.class),
                mock(vip.mate.memory.service.MemorySummarizationService.class),
                mock(vip.mate.memory.service.MemoryRecallService.class),
                mock(vip.mate.memory.MemoryProperties.class),
                mock(vip.mate.memory.scheduler.DreamingScheduler.class),
                mock(vip.mate.workspace.document.WorkspaceFileService.class),
                verifier);

        assertThrows(MateClawException.class, () -> controller.triggerEmergence(5L),
                "memory of an agent owned by workspace 99 must not be touchable from workspace 1");
    }

    @Test
    void factListRejectsCrossWorkspaceAgent() {
        foreignAgentBoundToWorkspace1();
        FactController controller = new FactController(
                mock(vip.mate.memory.fact.repository.FactMapper.class),
                mock(vip.mate.memory.fact.repository.FactContradictionMapper.class),
                mock(vip.mate.memory.fact.projection.FactProjectionBuilder.class),
                mock(vip.mate.workspace.document.WorkspaceFileService.class),
                mock(vip.mate.memory.MemoryProperties.class),
                verifier);

        assertThrows(MateClawException.class, () -> controller.listFacts(5L, null),
                "facts of an agent owned by workspace 99 must not be listable from workspace 1");
    }
}
