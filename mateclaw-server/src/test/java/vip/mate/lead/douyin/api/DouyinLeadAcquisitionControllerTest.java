package vip.mate.lead.douyin.api;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinLeadAcquisitionControllerTest {

    @Test
    void startEndpointReturnsAsyncRunHandleWithoutWaitingForCompletion() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        DouyinLeadTemplateService templateService = mock(DouyinLeadTemplateService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                templateService,
                authService);
        UserEntity user = new UserEntity();
        user.setId(9L);
        when(authService.findByUsername("alice")).thenReturn(user);
        DouyinLeadAcquisitionRunResponse started = DouyinLeadAcquisitionRunResponse.started(10L, 20L, "running");
        when(runService.start(eq(7L), eq(9L), any(DouyinLeadAcquisitionInput.class)))
                .thenReturn(started);

        var response = controller.start(
                new DouyinLeadAcquisitionStartRequest(
                        "openclaw",
                        "most_liked",
                        1,
                        List.of(
                                CommentMatchRule.keyword("他叫木马"),
                                CommentMatchRule.keyword("对于99%的人用豆包就行了。")),
                        "你好",
                        false),
                7L,
                new TestingAuthenticationToken("alice", "pw"));

        assertThat(response.getData().status()).isEqualTo("running");
        assertThat(response.getData().runId()).isEqualTo("10");
        assertThat(response.getData().taskId()).isEqualTo("20");
        assertThat(response.getData().commentsCollected()).isZero();
        assertThat(response.getData().events()).isEmpty();
        verify(runService).start(eq(7L), eq(9L), any(DouyinLeadAcquisitionInput.class));
        verify(runService, never()).runSync(any(), any(), any(), any());
    }

    @Test
    void streamEndpointDelegatesCursorInputsToEventStreamService() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        DouyinLeadTemplateService templateService = mock(DouyinLeadTemplateService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                templateService,
                authService);
        SseEmitter emitter = new SseEmitter();
        when(eventStreamService.stream(eq(10L), eq("123"), eq(456L))).thenReturn(emitter);

        SseEmitter response = controller.streamEvents(10L, 456L, "123");

        assertThat(response).isSameAs(emitter);
        verify(eventStreamService).stream(eq(10L), eq("123"), eq(456L));
    }

    @Test
    void recentRunsEndpointReturnsDouyinTaskHistoryForWorkspace() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        DouyinLeadTemplateService templateService = mock(DouyinLeadTemplateService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                templateService,
                authService);
        DouyinLeadRunListItem item = new DouyinLeadRunListItem(
                "10",
                "20",
                "易企秀",
                "most_liked",
                "succeeded",
                2,
                2,
                0,
                65,
                1,
                1,
                null,
                null,
                "2026-06-10T16:00:00",
                "2026-06-10T16:02:00");
        when(queryService.recentRuns(eq(7L), eq(12))).thenReturn(List.of(item));

        var response = controller.recentRuns(7L, 12);

        assertThat(response.getData()).containsExactly(item);
        verify(queryService).recentRuns(eq(7L), eq(12));
    }

    @Test
    void leadPoolEndpointReturnsCrossTaskMatchedLeads() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        DouyinLeadTemplateService templateService = mock(DouyinLeadTemplateService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                templateService,
                authService);
        DouyinLeadPoolItem lead = new DouyinLeadPoolItem(
                "10",
                "20",
                "易企秀",
                "most_liked",
                "succeeded",
                "30",
                "douyin-comment-1",
                "video-1",
                "霞姐一百岁",
                "https://www.douyin.com/user/abc",
                "慢出心脏病",
                1.0d,
                "exact_text_contains",
                "40",
                "50",
                "https://www.douyin.com/user/abc",
                "霞姐一百岁",
                "send_dm",
                "succeeded",
                true,
                "你好",
                null,
                null,
                "https://www.douyin.com/user/abc",
                "2026-06-10T16:00:00",
                "2026-06-10T16:02:00");
        when(queryService.leadPool(eq(7L), eq(25), eq("sent"), eq("易企秀"), eq(null))).thenReturn(List.of(lead));

        var response = controller.leadPool(7L, 25, "sent", "易企秀", null);

        assertThat(response.getData()).containsExactly(lead);
        verify(queryService).leadPool(eq(7L), eq(25), eq("sent"), eq("易企秀"), eq(null));
    }

    @Test
    void statsEndpointReturnsWorkspaceAcquisitionDashboard() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        DouyinLeadTemplateService templateService = mock(DouyinLeadTemplateService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                templateService,
                authService);
        DouyinLeadStatsDTO stats = new DouyinLeadStatsDTO(
                2,
                0,
                1,
                1,
                4,
                3,
                2,
                1,
                100,
                8,
                5,
                3,
                0.08d,
                0.625d,
                0.6d,
                List.of(new DouyinLeadStatsDTO.FailureReason("COMMENT_COLLECTION_INCOMPLETE", 1)));
        when(queryService.stats(eq(7L), eq(30), eq("易企秀"))).thenReturn(stats);

        var response = controller.stats(7L, 30, "易企秀");

        assertThat(response.getData()).isEqualTo(stats);
        verify(queryService).stats(eq(7L), eq(30), eq("易企秀"));
    }

    @Test
    void templateEndpointsDelegateToTemplateService() {
        DouyinLeadAcquisitionRunService runService = mock(DouyinLeadAcquisitionRunService.class);
        DouyinLeadAcquisitionQueryService queryService = mock(DouyinLeadAcquisitionQueryService.class);
        DouyinLeadAcquisitionEventStreamService eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        DouyinLeadTemplateService templateService = mock(DouyinLeadTemplateService.class);
        AuthService authService = mock(AuthService.class);
        DouyinLeadAcquisitionController controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                templateService,
                authService);
        UserEntity user = new UserEntity();
        user.setId(9L);
        when(authService.findByUsername("alice")).thenReturn(user);
        DouyinLeadTemplateRequest request = new DouyinLeadTemplateRequest(
                "产品吐槽线索",
                "易企秀",
                "most_liked",
                2,
                List.of(CommentMatchRule.keyword("慢出心脏病")),
                "你好",
                true,
                false);
        DouyinLeadTemplateDTO dto = new DouyinLeadTemplateDTO(
                "1",
                "产品吐槽线索",
                "易企秀",
                "most_liked",
                2,
                List.of(CommentMatchRule.keyword("慢出心脏病")),
                "你好",
                true,
                false,
                "2026-06-10T16:00:00",
                "2026-06-10T16:00:00");
        when(templateService.list(eq(7L))).thenReturn(List.of(dto));
        when(templateService.create(eq(7L), eq(9L), eq(request))).thenReturn(dto);
        when(templateService.update(eq(7L), eq(1L), eq(request))).thenReturn(dto);

        assertThat(controller.templates(7L).getData()).containsExactly(dto);
        assertThat(controller.createTemplate(request, 7L, new TestingAuthenticationToken("alice", "pw")).getData())
                .isEqualTo(dto);
        assertThat(controller.updateTemplate(1L, request, 7L).getData()).isEqualTo(dto);
        assertThat(controller.deleteTemplate(1L, 7L).getData()).containsEntry("deleted", true);

        verify(templateService).list(eq(7L));
        verify(templateService).create(eq(7L), eq(9L), eq(request));
        verify(templateService).update(eq(7L), eq(1L), eq(request));
        verify(templateService).delete(eq(7L), eq(1L));
    }
}
