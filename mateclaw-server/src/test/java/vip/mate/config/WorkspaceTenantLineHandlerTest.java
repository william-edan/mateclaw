package vip.mate.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTenantLineHandlerTest {

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void tenantColumnIsWorkspaceId() {
        assertEquals("workspace_id", new WorkspaceTenantLineHandler(Set.of()).getTenantIdColumn());
    }

    @Test
    void getTenantIdReadsBoundWorkspace() {
        WorkspaceTenantLineHandler handler = new WorkspaceTenantLineHandler(Set.of("mate_agent"));
        WorkspaceContextHolder.runWith(7L, () ->
                assertEquals("7", handler.getTenantId().toString()));
    }

    @Test
    void getTenantIdFailsClosedWhenNoContext() {
        WorkspaceTenantLineHandler handler = new WorkspaceTenantLineHandler(Set.of("mate_agent"));
        WorkspaceContextHolder.clear();
        assertThrows(IllegalStateException.class, handler::getTenantId);
    }

    @Test
    void ignoreTableRespectsWhitelistCaseInsensitively() {
        WorkspaceTenantLineHandler handler = new WorkspaceTenantLineHandler(Set.of("mate_agent"));
        assertFalse(handler.ignoreTable("mate_agent"), "whitelisted table must be scoped");
        assertFalse(handler.ignoreTable("MATE_AGENT"), "whitelist match is case-insensitive");
        assertTrue(handler.ignoreTable("mate_user"), "non-whitelisted table must be ignored");
        assertTrue(handler.ignoreTable(null));
    }

    @Test
    void emptyWhitelistIgnoresEveryTable() {
        WorkspaceTenantLineHandler handler = new WorkspaceTenantLineHandler(Set.of());
        assertTrue(handler.ignoreTable("mate_agent"));
        assertTrue(handler.ignoreTable("mate_fact"));
    }
}
