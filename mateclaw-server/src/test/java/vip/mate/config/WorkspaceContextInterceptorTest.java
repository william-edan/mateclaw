package vip.mate.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import vip.mate.workspace.core.WorkspaceContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceContextInterceptorTest {

    private final WorkspaceContextInterceptor interceptor = new WorkspaceContextInterceptor();

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    private static MockHttpServletRequest request(String workspaceHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (workspaceHeader != null) {
            request.addHeader("X-Workspace-Id", workspaceHeader);
        }
        return request;
    }

    @Test
    void preHandleBindsTheHeaderWorkspace() {
        interceptor.preHandle(request("20"), new MockHttpServletResponse(), new Object());
        assertEquals(20L, WorkspaceContextHolder.get());
    }

    @Test
    void preHandleClearsAnyStaleWorkspaceWhenHeaderAbsent() {
        WorkspaceContextHolder.set(99L); // left over on a reused thread

        interceptor.preHandle(request(null), new MockHttpServletResponse(), new Object());

        assertNull(WorkspaceContextHolder.get(),
                "a request without the header must not inherit a stale workspace from a pooled thread");
    }

    @Test
    void afterCompletionClearsTheBoundWorkspace() {
        WorkspaceContextHolder.set(20L);

        interceptor.afterCompletion(request("20"), new MockHttpServletResponse(), new Object(), null);

        assertNull(WorkspaceContextHolder.get());
    }
}
