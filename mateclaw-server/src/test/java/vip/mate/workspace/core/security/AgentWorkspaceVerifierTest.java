package vip.mate.workspace.core.security;

import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkspaceVerifierTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AgentWorkspaceVerifier verifier = new AgentWorkspaceVerifier(agentMapper);

    private AgentEntity agent(long id, Long ws) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setWorkspaceId(ws);
        return a;
    }

    @Test
    void passesWhenAgentInWorkspace() {
        when(agentMapper.selectById(10L)).thenReturn(agent(10L, 1L));
        assertDoesNotThrow(() -> verifier.verify(10L, 1L));
    }

    @Test
    void acceptsNumericStringAgentId() {
        when(agentMapper.selectById(10L)).thenReturn(agent(10L, 1L));
        assertDoesNotThrow(() -> verifier.verify("10", 1L));
    }

    @Test
    void rejectsCrossWorkspaceAgent() {
        when(agentMapper.selectById(10L)).thenReturn(agent(10L, 2L));
        assertThrows(MateClawException.class, () -> verifier.verify(10L, 1L));
    }

    @Test
    void rejectsMissingAgentFailClosed() {
        when(agentMapper.selectById(999L)).thenReturn(null);
        assertThrows(MateClawException.class, () -> verifier.verify(999L, 1L));
    }

    @Test
    void rejectsNullAgentIdFailClosed() {
        assertThrows(MateClawException.class, () -> verifier.verify((Long) null, 1L));
    }

    @Test
    void rejectsNonNumericStringAgentIdFailClosed() {
        assertThrows(MateClawException.class, () -> verifier.verify("not-a-number", 1L));
    }

    @Test
    void resolveWorkspaceReturnsAgentWorkspace() {
        when(agentMapper.selectById(10L)).thenReturn(agent(10L, 7L));
        assertEquals(7L, verifier.resolveWorkspace("10"));
    }

    @Test
    void resolveWorkspaceReturnsNullForMissingOrInvalid() {
        when(agentMapper.selectById(999L)).thenReturn(null);
        assertNull(verifier.resolveWorkspace("999"));
        assertNull(verifier.resolveWorkspace("not-a-number"));
        assertNull(verifier.resolveWorkspace(null));
    }
}
