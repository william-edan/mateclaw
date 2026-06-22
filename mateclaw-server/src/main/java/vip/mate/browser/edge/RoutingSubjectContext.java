package vip.mate.browser.edge;

/**
 * Thread-local carrier for the edge-routing subject of a background / service
 * task that has no per-conversation {@code ToolContext}.
 *
 * <p>Why this exists (契约3): the lead-acquisition flows (e.g. 抖音获客) run on
 * worker threads with no {@link org.springframework.ai.chat.model.ToolContext}
 * carrying a {@code ChatOrigin}, so
 * {@link vip.mate.tool.builtin.ExtensionBrowserTool#resolveSession} cannot derive
 * the originating user's subject from the call and would otherwise fall straight
 * through to the "exactly one browser online" heuristic — which mis-routes the
 * moment a second browser connects. The task entry point (group D) calls
 * {@link #set(String)} with the originator's edge subject (the PAT {@code userId}
 * string or the JWT username) and {@link #clear()} in a {@code finally} block;
 * {@code resolveSession} reads {@link #get()} as a precise routing key before
 * resorting to the single-session fallback.
 *
 * <p>Strictly opt-in and additive: when nothing is set {@link #get()} returns
 * {@code null} and all existing direct / offscreen / Claude-Code paths behave
 * exactly as before. ALWAYS pair {@link #set(String)} with {@link #clear()} in a
 * {@code finally} block — a worker thread is typically pooled and would leak a
 * stale subject onto the next task otherwise.
 *
 * <pre>{@code
 * RoutingSubjectContext.set(initiatorEdgeSubject);
 * try {
 *     // ... run the browser-driving task ...
 * } finally {
 *     RoutingSubjectContext.clear();
 * }
 * }</pre>
 */
public final class RoutingSubjectContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RoutingSubjectContext() {
    }

    /**
     * Bind the routing subject for the current thread. A {@code null} or blank
     * value is treated as "clear" so callers don't have to branch.
     */
    public static void set(String subject) {
        if (subject == null || subject.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(subject);
        }
    }

    /**
     * @return the routing subject bound to the current thread, or {@code null}
     *         when none is set.
     */
    public static String get() {
        return CURRENT.get();
    }

    /**
     * Unbind the routing subject for the current thread. Idempotent. MUST be
     * called from a {@code finally} block paired with {@link #set(String)} to
     * avoid leaking a subject onto a pooled thread's next task.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
