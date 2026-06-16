package vip.mate.workflow.v2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Part 1 (off-request 地基): workflow.v2 start runs off the request thread.
 * {@code resolveWorkspaceId} must prefer the explicit input, then the bound
 * {@link WorkspaceContextHolder}, and fail-closed when both are absent — it must
 * NOT silently default to workspace 1 (that would cross-tenant the run).
 */
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
