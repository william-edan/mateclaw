package vip.mate.lead.douyin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.model.LeadTaskEntity;
import vip.mate.os.run.repository.AgentEventMapper;
import vip.mate.os.run.repository.AgentRunMapper;
import vip.mate.os.run.repository.LeadCommentMapper;
import vip.mate.os.run.repository.LeadEngagementMapper;
import vip.mate.os.run.repository.LeadProfileMapper;
import vip.mate.os.run.repository.LeadTaskMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cross-workspace IDOR guard for the Douyin lead-acquisition read paths.
 *
 * <p>The {@code /runs/{runId}} and {@code /tasks/{taskId}/...} endpoints only
 * carry {@code @RequireWorkspaceRole("viewer")}, which proves the caller is a
 * member of the workspace they <em>claim</em> in {@code X-Workspace-Id} — it
 * does NOT prove the {@code runId}/{@code taskId} belongs to that workspace.
 * These tests pin the workspace-ownership guard that closes the leak.
 */
class DouyinLeadAcquisitionQueryServiceWorkspaceIsolationTest {

    private AgentRunMapper runMapper;
    private LeadTaskMapper taskMapper;
    private DouyinLeadAcquisitionQueryService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentRunMapper.class);
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        taskMapper = mock(LeadTaskMapper.class);
        LeadCommentMapper commentMapper = mock(LeadCommentMapper.class);
        LeadProfileMapper profileMapper = mock(LeadProfileMapper.class);
        LeadEngagementMapper engagementMapper = mock(LeadEngagementMapper.class);
        service = new DouyinLeadAcquisitionQueryService(
                runMapper, eventMapper, taskMapper, commentMapper, profileMapper, engagementMapper,
                new ObjectMapper());
    }

    @Test
    void assertRunInWorkspaceRejectsRunFromOtherWorkspace() {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(5L);
        run.setWorkspaceId(99L);
        when(runMapper.selectById(5L)).thenReturn(run);

        assertThrows(MateClawException.class, () -> service.assertRunInWorkspace(1L, 5L),
                "a run owned by workspace 99 must not be readable from workspace 1");
    }

    @Test
    void assertRunInWorkspaceAllowsRunFromOwnWorkspace() {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(5L);
        run.setWorkspaceId(1L);
        when(runMapper.selectById(5L)).thenReturn(run);

        assertDoesNotThrow(() -> service.assertRunInWorkspace(1L, 5L));
    }

    @Test
    void assertTaskInWorkspaceRejectsTaskFromOtherWorkspace() {
        LeadTaskEntity task = new LeadTaskEntity();
        task.setId(7L);
        task.setWorkspaceId(99L);
        when(taskMapper.selectById(7L)).thenReturn(task);

        assertThrows(MateClawException.class, () -> service.assertTaskInWorkspace(1L, 7L),
                "a task owned by workspace 99 must not expose comments/profiles/engagements to workspace 1");
    }

    @Test
    void assertTaskInWorkspaceAllowsTaskFromOwnWorkspace() {
        LeadTaskEntity task = new LeadTaskEntity();
        task.setId(7L);
        task.setWorkspaceId(1L);
        when(taskMapper.selectById(7L)).thenReturn(task);

        assertDoesNotThrow(() -> service.assertTaskInWorkspace(1L, 7L));
    }
}
