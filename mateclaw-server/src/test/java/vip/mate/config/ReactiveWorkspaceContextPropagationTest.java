package vip.mate.config;

import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;
import vip.mate.workspace.core.WorkspaceContextHolder;
import vip.mate.workspace.core.WorkspaceThreadLocalAccessor;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Proves the §5.2 bridge: with the accessor registered and automatic context
 * propagation on, a workspace bound at subscription survives a real scheduler
 * hop ({@code publishOn}) and is readable from {@link WorkspaceContextHolder}
 * on the worker thread — the exact failure mode that left the agent reactive
 * path mis-scoped.
 */
class ReactiveWorkspaceContextPropagationTest {

    @BeforeAll
    static void enablePropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new WorkspaceThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
    }

    @AfterAll
    static void disablePropagation() {
        // Global switch — turn it back off so it cannot leak into other test classes.
        Hooks.disableAutomaticContextPropagation();
    }

    @AfterEach
    void clear() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void workspaceSurvivesPublishOnToAnotherScheduler() {
        String mainThread = Thread.currentThread().getName();
        AtomicReference<String> workerThread = new AtomicReference<>();

        WorkspaceContextHolder.set(42L);
        Long observedOnWorker = Flux.just(0)
                .publishOn(Schedulers.boundedElastic())
                .map(i -> {
                    workerThread.set(Thread.currentThread().getName());
                    return WorkspaceContextHolder.get();
                })
                .blockLast();

        assertNotEquals(mainThread, workerThread.get(),
                "map must actually run on a different (boundedElastic) thread for this to prove anything");
        assertEquals(42L, observedOnWorker,
                "workspace bound at subscription must be restored on the worker thread");
    }
}
