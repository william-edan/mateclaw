package vip.mate.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.workspace.core.service.WorkspaceService;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceAccessInterceptorTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AuthService authService = mock(AuthService.class);
    private final WorkspaceAccessInterceptor interceptor = new WorkspaceAccessInterceptor(
            workspaceService, authService, mock(AgentMapper.class));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void workspaceScopedEndpointWithoutWorkspaceHeaderFailsClosed() throws Exception {
        assertFailsClosed(new MockHttpServletRequest());
    }

    @Test
    void workspaceScopedEndpointWithInvalidWorkspaceHeaderFailsClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "not-a-number");
        assertFailsClosed(request);
    }

    private void assertFailsClosed(MockHttpServletRequest request) throws Exception {
        UserEntity user = new UserEntity();
        user.setId(10L);
        user.setUsername("alice");
        user.setRole("user");
        when(authService.findByUsername("alice")).thenReturn(user);
        when(workspaceService.hasPermissionCached(1L, 10L, "viewer")).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(
                request,
                response,
                handlerMethod());

        assertFalse(allowed, "missing workspace header must not fall back to workspace 1");
        assertEquals(400, response.getStatus());
        verify(workspaceService, never()).hasPermissionCached(anyLong(), eq(10L), eq("viewer"));
    }

    private HandlerMethod handlerMethod() throws NoSuchMethodException {
        Method method = TestController.class.getDeclaredMethod("endpoint");
        return new HandlerMethod(new TestController(), method);
    }

    private static class TestController {
        @RequireWorkspaceRole("viewer")
        @SuppressWarnings("unused")
        public void endpoint() {
        }
    }
}
