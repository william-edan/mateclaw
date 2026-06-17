package vip.mate.workspace.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceContextHolderTest {

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void getIsNullWhenUnset() {
        assertNull(WorkspaceContextHolder.get());
    }

    @Test
    void setThenGet() {
        WorkspaceContextHolder.set(7L);
        assertEquals(7L, WorkspaceContextHolder.get());
    }

    @Test
    void callWithExposesValueDuringAction() {
        Long seen = WorkspaceContextHolder.callWith(7L, WorkspaceContextHolder::get);
        assertEquals(7L, seen);
    }

    @Test
    void callWithRestoresPreviousValue() {
        WorkspaceContextHolder.set(3L);

        WorkspaceContextHolder.callWith(7L, () -> {
            assertEquals(7L, WorkspaceContextHolder.get());
            return null;
        });

        assertEquals(3L, WorkspaceContextHolder.get(), "the prior workspace must be restored after the scope");
    }

    @Test
    void callWithClearsWhenNoPreviousValue() {
        WorkspaceContextHolder.callWith(7L, () -> null);
        assertNull(WorkspaceContextHolder.get(), "no value should linger when there was none before the scope");
    }

    @Test
    void runWithScopesValueAndClearsAfter() {
        AtomicReference<Long> seen = new AtomicReference<>();
        WorkspaceContextHolder.runWith(7L, () -> seen.set(WorkspaceContextHolder.get()));
        assertEquals(7L, seen.get());
        assertNull(WorkspaceContextHolder.get());
    }
}
