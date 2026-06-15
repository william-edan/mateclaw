package vip.mate.lead.douyin;

import org.junit.jupiter.api.Test;
import vip.mate.lead.douyin.browser.DouyinBrowserAdapter;
import vip.mate.lead.douyin.browser.DouyinBrowserException;
import vip.mate.lead.douyin.match.CommentMatcher;
import vip.mate.lead.douyin.model.CommentCollectionResult;
import vip.mate.lead.douyin.model.DouyinCommentItem;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.DouyinLeadRunSummary;
import vip.mate.lead.douyin.model.EngagementResult;
import vip.mate.lead.douyin.store.LeadPersistenceService;
import vip.mate.os.run.model.AgentStepEntity;
import vip.mate.os.run.model.LeadProfileEntity;
import vip.mate.os.run.runtime.AgentRunKernel;
import vip.mate.os.run.runtime.AgentStepRequest;
import vip.mate.os.run.runtime.RunCancellationService;
import vip.mate.os.run.runtime.RunEventPublisher;
import vip.mate.os.run.runtime.StepLedgerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinLeadAcquisitionExecutorTest {

    @Test
    void videoLimitOneKeepsSingleVideoCompatibilityPathAndEngagesExactMatch() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });
        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setId(33L);
        when(persistence.saveProfile(eq(20L), eq(10L), any())).thenReturn(profile);
        when(persistence.findCommentId(eq(20L), eq("exact"))).thenReturn(44L);

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "openclaw",
                "most_liked",
                1,
                List.of(CommentMatchRule.keyword("对于99%的人用豆包就行了。")),
                "你好",
                false,
                true));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_comments",
                "detect_region",
                "collect_comments",
                "engage:Ly");
        verify(persistence).saveComments(eq(20L), eq(10L), any());
        verify(persistence).markMatches(eq(20L), any());
        verify(persistence).saveEngagement(eq(20L), eq(10L), eq(33L), eq(44L),
                eq("dm_draft"), any(), eq("你好"));
        verify(persistence).completeTask(eq(20L), eq("succeeded"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.requestedVideoLimit() == 1
                        && summary.processedVideos() == 1
                        && summary.succeededVideos() == 1
                        && summary.failedVideos() == 0
                        && summary.engagementsCreated() == 1));
        verify(runKernel).finishSucceeded(eq(10L), eq("lead-task:20"));
        verify(runKernel, never()).finishFailed(eq(10L), any(), any());
    }

    @Test
    void multiVideoRunContinuesAfterOneVideoOpenFailure() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        browser.failingVideoIndexes.add(0);
        browser.collectionsByVideo.put(1, new CommentCollectionResult(
                List.of(browser.secondExact),
                1,
                true,
                "END_OF_LIST",
                1));
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });
        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setId(34L);
        when(persistence.saveProfile(eq(20L), eq(10L), any())).thenReturn(profile);
        when(persistence.findCommentId(eq(20L), eq("second-exact"))).thenReturn(45L);

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "openclaw",
                "most_liked",
                2,
                List.of(CommentMatchRule.keyword("第二条目标评论")),
                "你好",
                false,
                true));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_video:1",
                "open_comments",
                "detect_region",
                "collect_comments",
                "engage:Kai");
        verify(persistence).saveComments(eq(20L), eq(10L),
                argThat(comments -> comments != null && comments.size() == 1
                        && "second-exact".equals(comments.getFirst().commentKey())));
        verify(persistence).markMatches(eq(20L),
                argThat(matches -> matches != null && matches.size() == 1
                        && "second-exact".equals(matches.getFirst().comment().commentKey())));
        verify(persistence).saveEngagement(eq(20L), eq(10L), eq(34L), eq(45L),
                eq("dm_draft"), any(), eq("你好"));
        verify(persistence).completeTask(eq(20L), eq("succeeded"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.requestedVideoLimit() == 2
                        && summary.processedVideos() == 2
                        && summary.succeededVideos() == 1
                        && summary.failedVideos() == 1
                        && summary.commentsCollected() == 1
                        && summary.matchedComments() == 1
                        && summary.engagementsCreated() == 1
                        && "VIDEO_OPEN_FAILED".equals(summary.videoResults().getFirst().get("failureCode"))
                        && "succeeded".equals(summary.videoResults().get(1).get("status"))));
        verify(runKernel).finishSucceeded(eq(10L), eq("lead-task:20"));
        verify(runKernel, never()).finishFailed(eq(10L), any(), any());
    }

    @Test
    void multiVideoRunFinishesCurrentVideoEngagementBeforeOpeningNextVideo() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        browser.collectionsByVideo.put(0, new CommentCollectionResult(
                List.of(browser.exact),
                1,
                true,
                "END_OF_LIST",
                1));
        browser.collectionsByVideo.put(1, new CommentCollectionResult(
                List.of(browser.near),
                1,
                true,
                "END_OF_LIST",
                1));
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });
        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setId(33L);
        when(persistence.saveProfile(eq(20L), eq(10L), any())).thenReturn(profile);
        when(persistence.findCommentId(eq(20L), eq("exact"))).thenReturn(44L);

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "易企秀",
                "most_liked",
                2,
                List.of(CommentMatchRule.keyword("对于99%的人用豆包就行了")),
                "你好",
                false,
                true));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_comments",
                "detect_region",
                "collect_comments",
                "engage:Ly",
                "open_video:1",
                "open_comments",
                "detect_region",
                "collect_comments");
        verify(persistence).completeTask(eq(20L), eq("succeeded"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.requestedVideoLimit() == 2
                        && summary.processedVideos() == 2
                        && summary.succeededVideos() == 2
                        && summary.failedVideos() == 0
                        && summary.matchedComments() == 1
                        && summary.engagementsCreated() == 1));
        verify(runKernel).finishSucceeded(eq(10L), eq("lead-task:20"));
        verify(runKernel, never()).finishFailed(eq(10L), any(), any());
    }

    @Test
    void engageFalseSkipsInteractionsEvenWhenCommentsMatch() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "ai数字化转型",
                "most_liked",
                1,
                List.of(CommentMatchRule.keyword("对于99%的人用豆包就行了。")),
                "你好",
                false,
                false));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_comments",
                "detect_region",
                "collect_comments");
        verify(persistence).saveComments(eq(20L), eq(10L), any());
        verify(persistence).markMatches(eq(20L), any());
        verify(persistence, never()).saveProfile(any(), any(), any());
        verify(persistence, never()).saveEngagement(any(), any(), any(), any(), any(), any(), any());
        verify(persistence).completeTask(eq(20L), eq("succeeded"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.requestedVideoLimit() == 1
                        && summary.matchedComments() == 1
                        && summary.engagementsCreated() == 0));
        verify(runKernel).finishSucceeded(eq(10L), eq("lead-task:20"));
        verify(runKernel, never()).finishFailed(eq(10L), any(), any());
    }

    @Test
    void duplicateMatchedAuthorIsEngagedOncePerVideo() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        DouyinCommentItem first = FakeDouyinBrowserAdapter.comment("dup-1", "同一个作者", "目标评论 第一条");
        DouyinCommentItem second = FakeDouyinBrowserAdapter.comment("dup-2", "同一个作者", "目标评论 第二条");
        browser.collection = new CommentCollectionResult(
                List.of(first, second),
                2,
                true,
                "END_OF_LIST",
                1);
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });
        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setId(33L);
        when(persistence.saveProfile(eq(20L), eq(10L), any())).thenReturn(profile);
        when(persistence.findCommentId(eq(20L), eq("dup-1"))).thenReturn(44L);

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "易企秀",
                "most_liked",
                1,
                List.of(CommentMatchRule.keyword("目标评论")),
                "你好",
                false,
                true));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_comments",
                "detect_region",
                "collect_comments",
                "engage:同一个作者");
        verify(persistence).markMatches(eq(20L),
                argThat(matches -> matches != null && matches.size() == 2));
        verify(persistence).saveEngagement(eq(20L), eq(10L), eq(33L), eq(44L),
                eq("dm_draft"), any(), eq("你好"));
        verify(persistence).completeTask(eq(20L), eq("succeeded"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.matchedComments() == 2
                        && summary.engagementsCreated() == 1));
    }

    @Test
    void collectionOnlyDebugRunSkipsMatchingWhenNoRuleProvided() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "易企秀",
                "most_liked",
                1,
                List.of(),
                "你好",
                false,
                false));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_comments",
                "detect_region",
                "collect_comments");
        verify(persistence).saveComments(eq(20L), eq(10L), any());
        verify(persistence, never()).markMatches(eq(20L), any());
        verify(persistence, never()).saveProfile(any(), any(), any());
        verify(persistence).completeTask(eq(20L), eq("succeeded"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.requestedVideoLimit() == 1
                        && summary.matchedComments() == 0
                        && summary.engagementsCreated() == 0));
        verify(runKernel).finishSucceeded(eq(10L), eq("lead-task:20"));
        verify(runKernel, never()).finishFailed(eq(10L), any(), any());
    }

    @Test
    void sendDmFailureIsRecordedWithoutBlockingVideoOrRun() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        browser.engagement = new EngagementResult(
                browser.exact,
                browser.exact.authorName(),
                browser.exact.authorProfileUrl(),
                true,
                true,
                true,
                true,
                false,
                "failed",
                "DM_SEND_NOT_CONFIRMED",
                "未能确认私信已发送");
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });
        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setId(33L);
        when(persistence.saveProfile(eq(20L), eq(10L), any())).thenReturn(profile);
        when(persistence.findCommentId(eq(20L), eq("exact"))).thenReturn(44L);

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "openclaw",
                "most_liked",
                1,
                List.of(CommentMatchRule.keyword("对于99%的人用豆包就行了。")),
                "你好",
                true,
                true));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_comments",
                "detect_region",
                "collect_comments",
                "engage:Ly");
        verify(persistence).saveEngagement(eq(20L), eq(10L), eq(33L), eq(44L),
                eq("dm_draft"),
                argThat(result -> "failed".equals(result.status())
                        && "DM_SEND_NOT_CONFIRMED".equals(result.failureCode())),
                eq("你好"));
        verify(persistence).completeTask(eq(20L), eq("succeeded"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.requestedVideoLimit() == 1
                        && summary.processedVideos() == 1
                        && summary.succeededVideos() == 1
                        && summary.failedVideos() == 0
                        && summary.engagementsCreated() == 1
                        && "".equals(summary.videoResults().getFirst().get("failureCode"))));
        verify(runKernel).finishSucceeded(eq(10L), eq("lead-task:20"));
        verify(runKernel, never()).finishFailed(eq(10L), any(), any());
    }

    @Test
    void incompleteCollectionStillMatchesAndEngagesCollectedCommentsWhenRulesExist() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        browser.collection = new CommentCollectionResult(
                List.of(browser.exact),
                50,
                false,
                "NETWORK_NOT_ADVANCING",
                12,
                Map.of("partialCollection", true));
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });
        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setId(33L);
        when(persistence.saveProfile(eq(20L), eq(10L), any())).thenReturn(profile);
        when(persistence.findCommentId(eq(20L), eq("exact"))).thenReturn(44L);

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "openclaw",
                "most_liked",
                1,
                List.of(CommentMatchRule.keyword("对于99%的人用豆包就行了。")),
                "你好",
                false,
                true));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_comments",
                "detect_region",
                "collect_comments",
                "engage:Ly");
        verify(persistence).saveComments(eq(20L), eq(10L), any());
        verify(persistence).markMatches(eq(20L),
                argThat(matches -> matches != null && matches.size() == 1
                        && "exact".equals(matches.getFirst().comment().commentKey())));
        verify(persistence).saveEngagement(eq(20L), eq(10L), eq(33L), eq(44L),
                eq("dm_draft"), any(), eq("你好"));
        verify(persistence).completeTask(eq(20L), eq("succeeded"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.requestedVideoLimit() == 1
                        && summary.processedVideos() == 1
                        && summary.succeededVideos() == 1
                        && summary.failedVideos() == 0
                        && summary.matchedComments() == 1
                        && "COMMENT_COLLECTION_PARTIAL".equals(
                                summary.videoResults().getFirst().get("warningCode"))));
        verify(runKernel).finishSucceeded(eq(10L), eq("lead-task:20"));
        verify(runKernel, never()).finishFailed(eq(10L), any(), any());
    }

    @Test
    void incompleteCommentCollectionStopsBeforeMatchingAndEngagement() {
        FakeDouyinBrowserAdapter browser = new FakeDouyinBrowserAdapter();
        browser.collection = new CommentCollectionResult(
                List.of(browser.near),
                50,
                false,
                "PROTECTION_LIMIT",
                12,
                Map.of("partialCollection", true));
        LeadPersistenceService persistence = mock(LeadPersistenceService.class);
        AgentRunKernel runKernel = mock(AgentRunKernel.class);
        StepLedgerService steps = mock(StepLedgerService.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCancellationService cancellation = mock(RunCancellationService.class);
        AtomicLong stepIds = new AtomicLong(1);
        when(cancellation.isCancellationRequested(10L)).thenReturn(false);
        when(steps.openStep(any(AgentStepRequest.class))).thenAnswer(invocation -> {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(stepIds.getAndIncrement());
            step.setRunId(10L);
            step.setStepKey(invocation.getArgument(0, AgentStepRequest.class).stepKey());
            return step;
        });

        DouyinLeadAcquisitionExecutor executor = new DouyinLeadAcquisitionExecutor(
                browser,
                new CommentMatcher(),
                persistence,
                runKernel,
                steps,
                events,
                cancellation);

        executor.execute(10L, 20L, new DouyinLeadAcquisitionInput(
                "openclaw",
                "most_liked",
                1,
                List.of(),
                "你好",
                false,
                true));

        assertThat(browser.calls).containsExactly(
                "search",
                "sort",
                "open_video:0",
                "open_comments",
                "detect_region",
                "collect_comments");
        verify(persistence).saveComments(eq(20L), eq(10L), any());
        verify(persistence, never()).markMatches(eq(20L), any());
        verify(persistence, never()).saveProfile(any(), any(), any());
        verify(persistence).completeTask(eq(20L), eq("failed"),
                argThat((DouyinLeadRunSummary summary) -> summary != null
                        && summary.requestedVideoLimit() == 1
                        && summary.processedVideos() == 1
                        && summary.succeededVideos() == 0
                        && summary.failedVideos() == 1
                        && "COMMENT_COLLECTION_INCOMPLETE".equals(
                                summary.videoResults().getFirst().get("failureCode"))));
        verify(runKernel).finishFailed(eq(10L), eq("COMMENT_COLLECTION_INCOMPLETE"),
                argThat(message -> message != null && message.contains("stopReason=PROTECTION_LIMIT")));
        verify(runKernel, never()).finishSucceeded(eq(10L), any());
    }

    private static final class FakeDouyinBrowserAdapter implements DouyinBrowserAdapter {
        final List<String> calls = new ArrayList<>();
        final DouyinCommentItem near = comment("near", "宝宝甜妹",
                "九成以上的普通人目前根本没必要用这个东西，目前应用场景也就是一些电脑端的工作可以使用");
        final DouyinCommentItem exact = comment("exact", "Ly", "对于99%的人用豆包就行了。");
        final DouyinCommentItem secondExact = comment("video-1", "second-exact", "Kai", "第二条目标评论");
        final java.util.Set<Integer> failingVideoIndexes = new java.util.LinkedHashSet<>();
        final java.util.Map<Integer, CommentCollectionResult> collectionsByVideo = new java.util.LinkedHashMap<>();
        int currentVideoIndex = 0;
        CommentCollectionResult collection = new CommentCollectionResult(
                List.of(near, exact),
                2,
                true,
                "END_OF_LIST",
                1);
        EngagementResult engagement;

        @Override
        public BrowserObservation openDouyinAndSearch(DouyinLeadAcquisitionInput input) {
            calls.add("search");
            return observation();
        }

        @Override
        public BrowserObservation applySort(DouyinLeadAcquisitionInput input) {
            calls.add("sort");
            return observation();
        }

        @Override
        public BrowserObservation openVideo(int zeroBasedIndex) {
            calls.add("open_video:" + zeroBasedIndex);
            if (failingVideoIndexes.contains(zeroBasedIndex)) {
                throw new DouyinBrowserException("VIDEO_OPEN_FAILED", "video " + zeroBasedIndex + " failed");
            }
            currentVideoIndex = zeroBasedIndex;
            return observation();
        }

        @Override
        public BrowserObservation openComments() {
            calls.add("open_comments");
            return observation();
        }

        @Override
        public RegionInfo detectCommentRegion() {
            calls.add("detect_region");
            return RegionInfo.comments(600, 0, 400, 700, "test");
        }

        @Override
        public CommentCollectionResult collectAllComments(RegionInfo region) {
            calls.add("collect_comments");
            return collectionsByVideo.getOrDefault(currentVideoIndex, collection);
        }

        @Override
        public BrowserObservation openAuthorProfile(DouyinCommentItem comment) {
            calls.add("open_profile:" + comment.authorName());
            return observation();
        }

        @Override
        public EngagementResult followAndDraft(DouyinCommentItem comment, String dmDraft, boolean sendDm) {
            calls.add("engage:" + comment.authorName());
            if (engagement != null) {
                return engagement;
            }
            return new EngagementResult(
                    comment,
                    comment.authorName(),
                    comment.authorProfileUrl(),
                    true,
                    true,
                    true,
                    true,
                    false,
                    "succeeded",
                    null,
                    null);
        }

        private BrowserObservation observation() {
            return new BrowserObservation(true, "https://www.douyin.com/", "douyin", "", 1280, 720, "", "");
        }

        private static DouyinCommentItem comment(String key, String author, String text) {
            return comment("video", key, author, text);
        }

        private static DouyinCommentItem comment(String videoKey, String key, String author, String text) {
            return new DouyinCommentItem(
                    videoKey,
                    key,
                    null,
                    author,
                    "https://www.douyin.com/user/" + author,
                    null,
                    text,
                    null,
                    null,
                    new DouyinCommentItem.ClickTarget(900d, 300d, null, null),
                    null);
        }
    }
}
