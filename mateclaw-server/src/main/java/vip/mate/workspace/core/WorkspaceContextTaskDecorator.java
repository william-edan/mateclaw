package vip.mate.workspace.core;

import org.springframework.core.task.TaskDecorator;

/**
 * Carries the {@link WorkspaceContextHolder} workspace from the thread that
 * submits a task to the thread that runs it. {@code @Async} / virtual-thread
 * pools otherwise execute with no workspace bound, so a future tenant
 * interceptor would mis-scope their DB work to the default workspace.
 *
 * <p>The capture happens in {@link #decorate} — invoked on the SUBMITTING
 * thread at execute()-time — and is re-bound (then restored) around the task
 * on the executing thread via {@link WorkspaceContextHolder#runWith}.
 */
public class WorkspaceContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Long captured = WorkspaceContextHolder.get();
        return () -> WorkspaceContextHolder.runWith(captured, runnable);
    }
}
