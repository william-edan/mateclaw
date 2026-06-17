package vip.mate.workspace.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceContextTaskDecoratorTest {

    private final WorkspaceContextTaskDecorator decorator = new WorkspaceContextTaskDecorator();

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void propagatesWorkspaceCapturedAtSubmitTimeToTheExecutingThread() {
        AtomicReference<Long> seen = new AtomicReference<>();
        Runnable task = () -> seen.set(WorkspaceContextHolder.get());

        // decorate() runs on the submitting thread, where workspace 42 is bound.
        Runnable decorated = WorkspaceContextHolder.callWith(42L, () -> decorator.decorate(task));

        // Simulate the executing (async) thread: no ambient workspace here.
        assertNull(WorkspaceContextHolder.get());
        decorated.run();

        assertEquals(42L, seen.get(), "the task must observe the workspace captured when it was submitted");
        assertNull(WorkspaceContextHolder.get(), "the executing thread's context must be restored after the task");
    }

    @Test
    void bindsNothingWhenNoWorkspaceWasCaptured() {
        AtomicReference<Long> seen = new AtomicReference<>(-1L);
        Runnable decorated = decorator.decorate(() -> seen.set(WorkspaceContextHolder.get()));

        decorated.run();

        assertNull(seen.get(), "with no workspace at submit time the task must run unbound, not on a stale value");
    }
}
