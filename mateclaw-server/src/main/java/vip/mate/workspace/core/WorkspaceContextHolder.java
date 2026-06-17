package vip.mate.workspace.core;

import java.util.function.Supplier;

/**
 * Thread-bound "current workspace" context.
 *
 * <p>The HTTP path resolves the workspace from the {@code X-Workspace-Id}
 * header via {@code RequestContextHolder}. But agent runs, {@code @Async}
 * tasks, cron jobs and IM-channel ingestion execute off the request thread,
 * where that header is gone — so any code reading the "current workspace"
 * there silently falls back to the default workspace. This holder lets those
 * off-request execution paths carry the real workspace explicitly, and is the
 * foundation a future {@code TenantLineInnerInterceptor} reads from (so its
 * {@code getTenantId()} never mis-scopes async DB work to workspace 1).
 *
 * <p>Prefer the scoped {@link #callWith(Long, Supplier)} / {@link #runWith}
 * helpers over raw {@link #set}/{@link #clear}: they restore the previous
 * value in a {@code finally}, so a nested or pooled-thread context never leaks.
 */
public final class WorkspaceContextHolder {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private WorkspaceContextHolder() {
    }

    /** The workspace bound to the current thread, or {@code null} when unset. */
    public static Long get() {
        return CURRENT.get();
    }

    public static void set(Long workspaceId) {
        if (workspaceId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(workspaceId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Run {@code action} with {@code workspaceId} bound, restoring the prior value afterwards. */
    public static <T> T callWith(Long workspaceId, Supplier<T> action) {
        Long previous = CURRENT.get();
        set(workspaceId);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    public static void runWith(Long workspaceId, Runnable action) {
        callWith(workspaceId, () -> {
            action.run();
            return null;
        });
    }
}
