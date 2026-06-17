package vip.mate.tool.guard.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.tool.guard.service.ToolGuardAuditService;
import vip.mate.tool.guard.service.ToolGuardConfigService;
import vip.mate.tool.guard.service.ToolGuardRuleService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.core.WorkspaceContextHolder;
import vip.mate.workspace.core.security.AgentWorkspaceVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /security/approvals} is @RequireWorkspaceRole("admin") but historically
 * (a) returned any conversation's pending approvals by guessed conversationId and
 * (b) returned every workspace's pending rows on the global path. These pin the
 * workspace scoping: conversation ownership for the per-conversation path, and
 * per-row agent→workspace filtering for the global path.
 */
class SecurityControllerApprovalIsolationTest {

    private final ApprovalWorkflowService approvalWorkflowService = mock(ApprovalWorkflowService.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final SecurityController controller = new SecurityController(
            mock(ToolGuardConfigService.class),
            mock(ToolGuardRuleService.class),
            mock(ToolGuardAuditService.class),
            approvalWorkflowService,
            new AgentWorkspaceVerifier(agentMapper),
            conversationService);

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    private void agentInWorkspace(long agentId, long workspaceId) {
        AgentEntity a = new AgentEntity();
        a.setId(agentId);
        a.setWorkspaceId(workspaceId);
        when(agentMapper.selectById(agentId)).thenReturn(a);
    }

    @Test
    void byConversationRejectsCrossWorkspaceConversation() {
        WorkspaceContextHolder.set(1L);
        when(conversationService.isConversationInWorkspace("c-foreign", 1L)).thenReturn(false);

        assertThrows(MateClawException.class, () -> controller.listApprovals("c-foreign", 0),
                "a conversation owned by another workspace must not be readable by guessed id");
    }

    @Test
    void globalListFiltersByWorkspace() {
        WorkspaceContextHolder.set(1L);
        agentInWorkspace(5L, 99L);   // foreign
        agentInWorkspace(10L, 1L);   // own
        when(approvalWorkflowService.listPendingFromDb(anyInt())).thenReturn(List.of(
                Map.of("pendingId", "p-foreign", "agentId", "5"),
                Map.of("pendingId", "p-own", "agentId", "10")));

        R<Object> result = controller.listApprovals(null, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.getData();
        assertEquals(1, data.size(), "only the current workspace's pending approval must be visible");
        assertEquals("p-own", data.get(0).get("pendingId"));
    }
}
