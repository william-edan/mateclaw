package vip.mate.llm.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelWorkspaceResolverTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        ModelWorkspaceResolver.clear();
    }

    @Test
    void explicitWorkspaceContextWinsWhenThereIsNoServletHeader() {
        ModelWorkspaceResolver.setCurrentWorkspaceId(20L);

        assertEquals(20L, ModelWorkspaceResolver.currentWorkspaceId());
    }

    @Test
    void servletHeaderStillWinsOverExplicitContext() {
        ModelWorkspaceResolver.setCurrentWorkspaceId(20L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", "30");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals(30L, ModelWorkspaceResolver.currentWorkspaceId());
    }

    @Test
    void emptyExplicitContextFallsBackToDefaultWorkspace() {
        ModelWorkspaceResolver.setCurrentWorkspaceId(null);

        assertEquals(1L, ModelWorkspaceResolver.currentWorkspaceId());
    }
}
