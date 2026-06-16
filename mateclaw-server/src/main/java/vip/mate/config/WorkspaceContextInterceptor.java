package vip.mate.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import vip.mate.workspace.core.WorkspaceContextHolder;

/**
 * Binds the {@code X-Workspace-Id} header into {@link WorkspaceContextHolder}
 * for the duration of every {@code /api/**} request, so that work spawned off
 * the request thread (e.g. {@code @Async} via a task decorator) can carry the
 * real workspace instead of falling back to the default.
 *
 * <p>Always sets (or clears) on {@code preHandle} so a value left behind on a
 * pooled/reused thread can never leak into a later request, and clears again
 * on {@code afterCompletion}.
 */
@Component
public class WorkspaceContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        WorkspaceContextHolder.set(parseWorkspaceId(request.getHeader("X-Workspace-Id")));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        WorkspaceContextHolder.clear();
    }

    static Long parseWorkspaceId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
