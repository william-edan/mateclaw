package vip.mate.workspace.core;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Micrometer context-propagation bridge for {@link WorkspaceContextHolder}.
 *
 * <p>The agent runs as a reactive {@code Flux} that hops schedulers
 * ({@code publishOn}/{@code flatMap}), where a plain ThreadLocal is lost — so
 * any operator reading the "current workspace" off the worker thread silently
 * mis-scopes. Registering this accessor + {@code Hooks.enableAutomaticContextPropagation()}
 * makes Reactor capture the bound workspace at subscription and restore it onto
 * every operator thread, so {@link WorkspaceContextHolder#get()} stays correct
 * across the reactive chain.
 *
 * <p>Wired (gated, default-off) by {@code ReactiveWorkspaceContextConfig}.
 */
public final class WorkspaceThreadLocalAccessor implements ThreadLocalAccessor<Long> {

    public static final String KEY = "mateclaw.workspaceId";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public Long getValue() {
        return WorkspaceContextHolder.get();
    }

    @Override
    public void setValue(Long value) {
        WorkspaceContextHolder.set(value);
    }

    @Override
    public void setValue() {
        WorkspaceContextHolder.clear();
    }
}
