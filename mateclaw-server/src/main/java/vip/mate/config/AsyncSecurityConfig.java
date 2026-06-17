package vip.mate.config;

import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.task.DelegatingSecurityContextTaskExecutor;
import vip.mate.workspace.core.WorkspaceContextTaskDecorator;

import java.util.concurrent.Executor;

/**
 * Global async executor config — ensures SecurityContext propagation to @Async threads.
 * <p>
 * The inner delegate uses virtual threads (JDK 21); the outer
 * {@link DelegatingSecurityContextTaskExecutor} wrapper propagates the caller's
 * SecurityContext (JWT identity, audit permissions) to every @Async invocation.
 *
 * @author MateClaw Team
 */
@Configuration
@EnableAsync
public class AsyncSecurityConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        // Keep DelegatingSecurityContextTaskExecutor so SecurityContext
        // is propagated to every @Async invocation (JWT, audit, permission checks).
        // Replace the inner platform-thread pool with a virtual-thread executor.
        var delegate = new SimpleAsyncTaskExecutorBuilder()
                .virtualThreads(true)
                .threadNamePrefix("async-vt-")
                // Carry the submitting thread's workspace onto the async thread so
                // off-request work resolves the real workspace, not the default.
                .taskDecorator(new WorkspaceContextTaskDecorator())
                .build();
        return new DelegatingSecurityContextTaskExecutor(delegate);
    }
}
