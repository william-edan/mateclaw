package vip.mate.lead.douyin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.model.LeadCommentEntity;
import vip.mate.os.run.model.LeadEngagementEntity;
import vip.mate.os.run.model.LeadProfileEntity;
import vip.mate.os.run.model.LeadTaskEntity;
import vip.mate.os.run.repository.AgentEventMapper;
import vip.mate.os.run.repository.AgentRunMapper;
import vip.mate.os.run.repository.LeadCommentMapper;
import vip.mate.os.run.repository.LeadEngagementMapper;
import vip.mate.os.run.repository.LeadProfileMapper;
import vip.mate.os.run.repository.LeadTaskMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinLeadAcquisitionQueryServiceTest {

    @Test
    void statsAggregatesRecentTaskPerformanceAndFailureReasons() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        LeadTaskMapper taskMapper = mock(LeadTaskMapper.class);
        LeadCommentMapper commentMapper = mock(LeadCommentMapper.class);
        LeadProfileMapper profileMapper = mock(LeadProfileMapper.class);
        LeadEngagementMapper engagementMapper = mock(LeadEngagementMapper.class);
        DouyinLeadAcquisitionQueryService service = new DouyinLeadAcquisitionQueryService(
                runMapper,
                eventMapper,
                taskMapper,
                commentMapper,
                profileMapper,
                engagementMapper,
                new ObjectMapper());

        LeadTaskEntity successTask = new LeadTaskEntity();
        successTask.setId(20L);
        successTask.setRunId(10L);
        successTask.setWorkspaceId(7L);
        successTask.setPlatform("douyin");
        successTask.setKeyword("易企秀");
        successTask.setSortMode("most_liked");
        successTask.setStatus("succeeded");
        successTask.setInputJson("{\"videoLimit\":2}");
        successTask.setSummaryJson("""
                {
                  "requestedVideoLimit": 2,
                  "processedVideos": 2,
                  "succeededVideos": 2,
                  "failedVideos": 0,
                  "commentsCollected": 80,
                  "matchedComments": 8,
                  "engagementsCreated": 2,
                  "videoResults": [
                    {"status":"succeeded","commentsCollected":65},
                    {"status":"succeeded","commentsCollected":15}
                  ]
                }
                """);

        LeadTaskEntity failedTask = new LeadTaskEntity();
        failedTask.setId(21L);
        failedTask.setRunId(11L);
        failedTask.setWorkspaceId(7L);
        failedTask.setPlatform("douyin");
        failedTask.setKeyword("易企秀");
        failedTask.setSortMode("most_liked");
        failedTask.setStatus("failed");
        failedTask.setInputJson("{\"videoLimit\":2}");
        failedTask.setSummaryJson("""
                {
                  "requestedVideoLimit": 2,
                  "processedVideos": 1,
                  "succeededVideos": 0,
                  "failedVideos": 1,
                  "commentsCollected": 20,
                  "matchedComments": 0,
                  "engagementsCreated": 0,
                  "videoResults": [
                    {
                      "status":"failed",
                      "commentsCollected":20,
                      "failureCode":"COMMENT_COLLECTION_INCOMPLETE"
                    }
                  ]
                }
                """);

        LeadTaskEntity queuedTask = new LeadTaskEntity();
        queuedTask.setId(22L);
        queuedTask.setWorkspaceId(7L);
        queuedTask.setPlatform("douyin");
        queuedTask.setKeyword("易企秀");
        queuedTask.setSortMode("most_liked");
        queuedTask.setStatus("queued");
        queuedTask.setInputJson("{\"videoLimit\":0}");
        queuedTask.setSummaryJson("""
                {
                  "requestedVideoLimit": 0,
                  "processedVideos": 0,
                  "succeededVideos": 0,
                  "failedVideos": 0,
                  "commentsCollected": 0,
                  "matchedComments": 0,
                  "engagementsCreated": 0,
                  "videoResults": []
                }
                """);

        AgentRunEntity succeededRun = new AgentRunEntity();
        succeededRun.setId(10L);
        succeededRun.setStatus("succeeded");
        AgentRunEntity failedRun = new AgentRunEntity();
        failedRun.setId(11L);
        failedRun.setStatus("failed");
        failedRun.setFailureCode("ALL_VIDEOS_FAILED");

        LeadEngagementEntity sent = new LeadEngagementEntity();
        sent.setId(30L);
        sent.setTaskId(20L);
        sent.setRunId(10L);
        sent.setEngagementType("send_dm");
        sent.setStatus("succeeded");

        LeadEngagementEntity failedEngagement = new LeadEngagementEntity();
        failedEngagement.setId(31L);
        failedEngagement.setTaskId(20L);
        failedEngagement.setRunId(10L);
        failedEngagement.setEngagementType("send_dm");
        failedEngagement.setStatus("failed");
        failedEngagement.setFailureCode("DM_PAGE_NOT_CONFIRMED");

        LeadEngagementEntity draftOnly = new LeadEngagementEntity();
        draftOnly.setId(32L);
        draftOnly.setTaskId(20L);
        draftOnly.setRunId(10L);
        draftOnly.setEngagementType("dm_draft");
        draftOnly.setStatus("succeeded");

        when(taskMapper.selectList(any())).thenReturn(List.of(successTask, failedTask, queuedTask));
        when(runMapper.selectBatchIds(any())).thenReturn(List.of(succeededRun, failedRun));
        when(engagementMapper.selectList(any())).thenReturn(List.of(sent, failedEngagement, draftOnly));

        DouyinLeadStatsDTO stats = service.stats(7L, 50, "易企秀");

        assertThat(stats.taskCount()).isEqualTo(3);
        assertThat(stats.runningTasks()).isEqualTo(1);
        assertThat(stats.succeededTasks()).isEqualTo(1);
        assertThat(stats.failedTasks()).isEqualTo(1);
        assertThat(stats.requestedVideos()).isEqualTo(4);
        assertThat(stats.processedVideos()).isEqualTo(3);
        assertThat(stats.succeededVideos()).isEqualTo(2);
        assertThat(stats.failedVideos()).isEqualTo(1);
        assertThat(stats.commentsCollected()).isEqualTo(100);
        assertThat(stats.matchedComments()).isEqualTo(8);
        assertThat(stats.engagementsCreated()).isEqualTo(2);
        assertThat(stats.sentMessages()).isEqualTo(1);
        assertThat(stats.matchRate()).isEqualTo(0.08d);
        assertThat(stats.engagementRate()).isEqualTo(0.25d);
        assertThat(stats.sendSuccessRate()).isEqualTo(0.5d);
        assertThat(stats.failureReasons())
                .extracting(DouyinLeadStatsDTO.FailureReason::reason)
                .contains("ALL_VIDEOS_FAILED", "COMMENT_COLLECTION_INCOMPLETE", "DM_PAGE_NOT_CONFIRMED");

        verify(runMapper).selectBatchIds(any());
        verify(engagementMapper).selectList(any());
        verify(commentMapper, never()).selectCount(any());
    }

    @Test
    void leadPoolBuildsCrossTaskRowsWithBatchLookups() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        LeadTaskMapper taskMapper = mock(LeadTaskMapper.class);
        LeadCommentMapper commentMapper = mock(LeadCommentMapper.class);
        LeadProfileMapper profileMapper = mock(LeadProfileMapper.class);
        LeadEngagementMapper engagementMapper = mock(LeadEngagementMapper.class);
        DouyinLeadAcquisitionQueryService service = new DouyinLeadAcquisitionQueryService(
                runMapper,
                eventMapper,
                taskMapper,
                commentMapper,
                profileMapper,
                engagementMapper,
                new ObjectMapper());

        LeadTaskEntity task = new LeadTaskEntity();
        task.setId(20L);
        task.setRunId(10L);
        task.setWorkspaceId(7L);
        task.setPlatform("douyin");
        task.setKeyword("易企秀");
        task.setSortMode("most_liked");
        task.setStatus("running");
        task.setCreateTime(LocalDateTime.parse("2026-06-10T16:00:00"));
        task.setUpdateTime(LocalDateTime.parse("2026-06-10T16:01:00"));

        AgentRunEntity run = new AgentRunEntity();
        run.setId(10L);
        run.setStatus("succeeded");

        LeadCommentEntity comment = new LeadCommentEntity();
        comment.setId(30L);
        comment.setTaskId(20L);
        comment.setRunId(10L);
        comment.setVideoKey("video-1");
        comment.setCommentKey("douyin-comment-1");
        comment.setAuthorName("霞姐一百岁");
        comment.setAuthorProfileUrl("https://www.douyin.com/user/abc");
        comment.setCommentText("慢出心脏病");
        comment.setMatched(true);
        comment.setMatchScore(1.0d);
        comment.setMatchReason("exact_text_contains");
        comment.setCreateTime(LocalDateTime.parse("2026-06-10T16:02:00"));

        LeadEngagementEntity engagement = new LeadEngagementEntity();
        engagement.setId(40L);
        engagement.setTaskId(20L);
        engagement.setRunId(10L);
        engagement.setCommentId(30L);
        engagement.setProfileId(50L);
        engagement.setEngagementType("send_dm");
        engagement.setStatus("succeeded");
        engagement.setDraftText("你好");
        engagement.setUpdateTime(LocalDateTime.parse("2026-06-10T16:03:00"));

        LeadProfileEntity profile = new LeadProfileEntity();
        profile.setId(50L);
        profile.setProfileUrl("https://www.douyin.com/user/abc");
        profile.setDisplayName("霞姐一百岁");

        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(runMapper.selectBatchIds(any())).thenReturn(List.of(run));
        when(commentMapper.selectList(any())).thenReturn(List.of(comment));
        when(engagementMapper.selectList(any())).thenReturn(List.of(engagement));
        when(profileMapper.selectBatchIds(any())).thenReturn(List.of(profile));

        List<DouyinLeadPoolItem> rows = service.leadPool(7L, 50, "sent", "易企秀", null);

        assertThat(rows).hasSize(1);
        DouyinLeadPoolItem row = rows.getFirst();
        assertThat(row.runId()).isEqualTo("10");
        assertThat(row.taskId()).isEqualTo("20");
        assertThat(row.keyword()).isEqualTo("易企秀");
        assertThat(row.runStatus()).isEqualTo("succeeded");
        assertThat(row.commentId()).isEqualTo("30");
        assertThat(row.profileUrl()).isEqualTo("https://www.douyin.com/user/abc");
        assertThat(row.displayName()).isEqualTo("霞姐一百岁");
        assertThat(row.sent()).isTrue();

        verify(runMapper).selectBatchIds(any());
        verify(profileMapper).selectBatchIds(any());
        verify(runMapper, never()).selectById(any());
        verify(profileMapper, never()).selectById(any());
        verify(engagementMapper, never()).selectOne(any());
    }
}
