package vip.mate.browser.edge;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.edge.session.BrowserSessionView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Direct instantiation tests for BrowserSessionDebugController.
 * The project convention (see WorkflowControllerTest) is to test controllers
 * directly rather than via MockMvc to avoid loading the full security stack.
 * The registry.snapshot() contract is verified here; HTTP layer is covered
 * by the EdgeEndpointSmokeTest integration test.
 */
class BrowserSessionDebugControllerTest {

    private final BrowserSessionRegistry registry = new BrowserSessionRegistry();
    private final BrowserSessionDebugController controller = new BrowserSessionDebugController(registry);

    @Test
    void list_emptyRegistry_returnsEmptyList() {
        List<BrowserSessionView> result = controller.list().getData();
        assertThat(result).isEmpty();
    }

    @Test
    void list_oneRegistered_returnsOneEntry() {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("ws-test");
        when(ws.isOpen()).thenReturn(true);
        var s = registry.register("alice", ws, "0.1.0");
        try {
            List<BrowserSessionView> result = controller.list().getData();
            assertThat(result).hasSize(1);
            BrowserSessionView view = result.get(0);
            assertThat(view.sessionId()).isEqualTo(s.getId());
            assertThat(view.subject()).isEqualTo("alice");
            assertThat(view.agentVersion()).isEqualTo("0.1.0");
            assertThat(view.lastHeartbeatAt()).isNotNull();
        } finally {
            registry.removeByWs("ws-test");
        }
    }
}
