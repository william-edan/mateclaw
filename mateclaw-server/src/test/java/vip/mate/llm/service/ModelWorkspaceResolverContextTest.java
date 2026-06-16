package vip.mate.llm.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.mate.workspace.core.WorkspaceContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ModelWorkspaceResolver} must prefer the thread-bound
 * {@link WorkspaceContextHolder} over the HTTP header, so off-request
 * execution paths (agent runs, async tasks, cron, channels) resolve the
 * REAL workspace instead of silently defaulting to workspace 1.
 */
class ModelWorkspaceResolverContextTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        WorkspaceContextHolder.clear();
    }

    private static void withHeader(long workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", Long.toString(workspaceId));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void contextWorkspaceWinsWithNoRequestContext() {
        // The async scenario: no HTTP request on this thread, but the workspace
        // was bound explicitly. Today this returns DEFAULT_WORKSPACE_ID (=1).
        long resolved = WorkspaceContextHolder.callWith(20L, ModelWorkspaceResolver::currentWorkspaceId);
        assertEquals(20L, resolved,
                "an explicitly bound workspace must win even without a request context");
    }

    @Test
    void contextWorkspaceTakesPrecedenceOverHeader() {
        withHeader(5L);
        long resolved = WorkspaceContextHolder.callWith(20L, ModelWorkspaceResolver::currentWorkspaceId);
        assertEquals(20L, resolved, "the bound context must override a stale request header");
    }

    @Test
    void fallsBackToHeaderWhenNoContext() {
        withHeader(5L);
        assertEquals(5L, ModelWorkspaceResolver.currentWorkspaceId());
    }

    @Test
    void fallsBackToDefaultWhenNeitherPresent() {
        assertEquals(ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID, ModelWorkspaceResolver.currentWorkspaceId());
    }
}
