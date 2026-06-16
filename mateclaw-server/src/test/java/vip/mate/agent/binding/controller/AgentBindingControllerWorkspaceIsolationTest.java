package vip.mate.agent.binding.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentBindingControllerWorkspaceIsolationTest {

    private AgentBindingService bindingService;
    private AgentService agentService;
    private AgentBindingController controller;

    @BeforeEach
    void setUp() {
        bindingService = mock(AgentBindingService.class);
        agentService = mock(AgentService.class);
        controller = new AgentBindingController(
                bindingService,
                agentService,
                mock(AuditEventService.class));
    }

    @Test
    void listSkillsFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.listSkills(42L, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(agentService, bindingService);
    }

    @Test
    void setToolsFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.setTools(42L, java.util.List.of("read_file"), null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(agentService, bindingService);
    }

    @Test
    void listSkillsRejectsAgentFromDifferentWorkspace() {
        AgentEntity agent = new AgentEntity();
        agent.setId(42L);
        agent.setWorkspaceId(2L);
        when(agentService.getAgent(42L)).thenReturn(agent);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.listSkills(42L, 1L));

        assertEquals(403, ex.getCode());
        assertEquals("err.common.wrong_workspace", ex.getMsgKey());
    }
}
