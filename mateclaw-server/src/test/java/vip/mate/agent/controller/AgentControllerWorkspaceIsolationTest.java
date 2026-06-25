package vip.mate.agent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.audit.service.AuditEventService;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.system.service.SystemSettingService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentControllerWorkspaceIsolationTest {

    private AgentService agentService;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        controller = new AgentController(
                agentService,
                mock(AuditEventService.class),
                mock(AuthService.class),
                mock(ModelConfigService.class),
                mock(ModelCapabilityService.class),
                mock(SystemSettingService.class));
    }

    @Test
    void listFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.list(null, null, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(agentService);
    }

    @Test
    void getFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.get(42L, null, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(agentService);
    }

    @Test
    void createFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.create(null, new AgentEntity(), null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(agentService);
    }

    @Test
    void getRejectsAgentFromDifferentWorkspace() {
        AgentEntity agent = new AgentEntity();
        agent.setId(42L);
        agent.setWorkspaceId(2L);
        when(agentService.getAgent(42L)).thenReturn(agent);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.get(42L, 1L, null));

        assertEquals(403, ex.getCode());
        assertEquals("err.common.wrong_workspace", ex.getMsgKey());
    }
}
