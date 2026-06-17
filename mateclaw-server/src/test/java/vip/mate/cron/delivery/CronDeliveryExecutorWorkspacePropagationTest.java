package vip.mate.cron.delivery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import vip.mate.audit.service.AuditEventService;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Part 1 (off-request 地基): cron delivery runs on the dedicated
 * {@code cronDeliveryExecutor} pool after T2 commit. The pool must carry the
 * submitting thread's workspace via {@link vip.mate.workspace.core.WorkspaceContextTaskDecorator}
 * so delivery resolves the real workspace rather than the default.
 */
class CronDeliveryExecutorWorkspacePropagationTest {

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void cronDeliveryExecutorCarriesWorkspaceToDeliveryThread() throws Exception {
        CronDeliveryListener listener =
                new CronDeliveryListener(List.of(), mock(AuditEventService.class));
        ThreadPoolTaskExecutor ex = listener.cronDeliveryExecutor();
        CompletableFuture<Long> observed = new CompletableFuture<>();

        WorkspaceContextHolder.callWith(42L, () -> {
            ex.execute(() -> observed.complete(WorkspaceContextHolder.get()));
            return null;
        });

        assertEquals(42L, observed.get(5, TimeUnit.SECONDS),
                "a cron delivery task must observe the workspace bound when it was submitted");
    }
}
