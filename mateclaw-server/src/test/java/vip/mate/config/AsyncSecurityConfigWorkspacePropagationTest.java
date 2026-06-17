package vip.mate.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check that the {@code @Async} executor configured by
 * {@link AsyncSecurityConfig} carries the submitting thread's workspace onto
 * the async (virtual) thread via the task decorator.
 */
class AsyncSecurityConfigWorkspacePropagationTest {

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void configuredExecutorPropagatesWorkspaceToTheAsyncThread() throws Exception {
        Executor executor = new AsyncSecurityConfig().getAsyncExecutor();
        CompletableFuture<Long> observed = new CompletableFuture<>();

        WorkspaceContextHolder.callWith(42L, () -> {
            executor.execute(() -> observed.complete(WorkspaceContextHolder.get()));
            return null;
        });

        assertEquals(42L, observed.get(5, TimeUnit.SECONDS),
                "an @Async task must observe the workspace bound when it was submitted");
    }
}
