package vip.mate.llm.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.mate.workspace.core.WorkspaceContextHolder;

public final class ModelWorkspaceResolver {

    public static final long DEFAULT_WORKSPACE_ID = 1L;

    private ModelWorkspaceResolver() {
    }

    public static long currentWorkspaceId() {
        // Off-request execution paths (agent runs, @Async, cron, channels) bind the
        // workspace explicitly via WorkspaceContextHolder; it must win over the request
        // header so async work is not silently mis-scoped to the default workspace.
        Long bound = WorkspaceContextHolder.get();
        if (bound != null) {
            return bound;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String header = request.getHeader("X-Workspace-Id");
            if (header != null && !header.isBlank()) {
                try {
                    return Long.parseLong(header.trim());
                } catch (NumberFormatException ignored) {
                    return DEFAULT_WORKSPACE_ID;
                }
            }
        }
        return DEFAULT_WORKSPACE_ID;
    }
}
