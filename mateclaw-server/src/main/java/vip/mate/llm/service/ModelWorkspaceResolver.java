package vip.mate.llm.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.mate.workspace.core.WorkspaceContextHolder;

public final class ModelWorkspaceResolver {

    public static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final ThreadLocal<Long> CURRENT_WORKSPACE_ID = new ThreadLocal<>();

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
        Long explicit = CURRENT_WORKSPACE_ID.get();
        if (explicit != null) {
            return explicit;
        }
        return DEFAULT_WORKSPACE_ID;
    }

    public static void setCurrentWorkspaceId(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            CURRENT_WORKSPACE_ID.remove();
            return;
        }
        CURRENT_WORKSPACE_ID.set(workspaceId);
    }

    public static void clear() {
        CURRENT_WORKSPACE_ID.remove();
    }
}
