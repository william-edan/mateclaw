package vip.mate.cron.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import vip.mate.agent.AgentService;
import vip.mate.cron.CronChatOriginFactory;
import vip.mate.cron.CronConversationResolver;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.wiki.service.WikiProcessingService;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Part 1 (off-request 地基): a scheduled cron run executes off the request thread,
 * so it must bind {@code job.workspaceId} onto {@link WorkspaceContextHolder} for
 * the duration of the run — otherwise downstream DB/tool/memory resolution silently
 * mis-scopes to the default workspace.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CronJobRunnerWorkspaceContextTest {

    @Mock CronJobLifecycleService lifecycle;
    @Mock AgentService agentService;
    @Mock CronChatOriginFactory originFactory;
    @Mock CronConversationResolver conversationResolver;
    @Mock WikiProcessingService wikiProcessingService;

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void executeJob_bindsWorkspaceContextDuringRun_andRestoresAfter() {
        CronJobRunner runner = new CronJobRunner(lifecycle, agentService, originFactory,
                conversationResolver, wikiProcessingService, new ObjectMapper());

        CronJobEntity job = new CronJobEntity();
        job.setId(1L);
        job.setWorkspaceId(7L);
        job.setTaskType("reminder");
        job.setTriggerMessage("hello");

        AtomicReference<Long> seenInsideRun = new AtomicReference<>();
        when(conversationResolver.resolve(job)).thenReturn("conv-1");
        when(lifecycle.startRun(eq(job), any(), any(), eq("conv-1"))).thenAnswer(inv -> {
            seenInsideRun.set(WorkspaceContextHolder.get());
            return new CronJobRunEntity();
        });

        runner.executeJob(job);

        assertThat(seenInsideRun.get())
                .as("executeJob must bind job.workspaceId for the duration of the run")
                .isEqualTo(7L);
        assertThat(WorkspaceContextHolder.get())
                .as("runWith must restore the previous (null) context after the run")
                .isNull();
    }
}
