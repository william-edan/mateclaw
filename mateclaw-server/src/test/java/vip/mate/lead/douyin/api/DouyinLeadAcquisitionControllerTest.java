package vip.mate.lead.douyin.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.workspace.core.service.WorkspaceService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinLeadAcquisitionControllerTest {

    private DouyinLeadAcquisitionRunService runService;
    private DouyinLeadAcquisitionQueryService queryService;
    private DouyinLeadAcquisitionEventStreamService eventStreamService;
    private DouyinLeadTemplateService templateService;
    private AuthService authService;
    private WorkspaceService workspaceService;
    private DouyinLeadAcquisitionController controller;

    @BeforeEach
    void setUp() {
        runService = mock(DouyinLeadAcquisitionRunService.class);
        queryService = mock(DouyinLeadAcquisitionQueryService.class);
        eventStreamService = mock(DouyinLeadAcquisitionEventStreamService.class);
        templateService = mock(DouyinLeadTemplateService.class);
        authService = mock(AuthService.class);
        workspaceService = mock(WorkspaceService.class);
        controller = new DouyinLeadAcquisitionController(
                runService,
                queryService,
                eventStreamService,
                templateService,
                authService,
                workspaceService);
    }

    @Test
    void startEndpointReturnsAsyncRunHandleWithoutWaitingForCompletion() {
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
                                CommentMatchRule.keyword("comment-rule-a"),
                                CommentMatchRule.keyword("comment-rule-b")),
                        "hello",
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
        SseEmitter emitter = new SseEmitter();
        // A run with no recorded workspace short-circuits the membership guard;
        // this test isolates pure cursor/last-event-id passthrough.
        when(queryService.runWorkspaceId(10L)).thenReturn(null);
        when(eventStreamService.stream(eq(10L), eq("123"), eq(456L))).thenReturn(emitter);

        SseEmitter response = controller.streamEvents(10L, 456L, "123",
                new TestingAuthenticationToken("alice", "pw"));

        assertThat(response).isSameAs(emitter);
        verify(eventStreamService).stream(eq(10L), eq("123"), eq(456L));
    }

    @Test
    void recentRunsEndpointReturnsDouyinTaskHistoryForWorkspace() {
        DouyinLeadRunListItem item = new DouyinLeadRunListItem(
                "10",
                "20",
                "keyword-x",
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
        DouyinLeadPoolItem lead = new DouyinLeadPoolItem(
                "10",
                "20",
                "keyword-x",
                "most_liked",
                "succeeded",
                "30",
                "douyin-comment-1",
                "video-1",
                "author-name",
                "https://www.douyin.com/user/abc",
                "comment-text",
                1.0d,
                "exact_text_contains",
                "40",
                "50",
                "https://www.douyin.com/user/abc",
                "author-name",
                "send_dm",
                "succeeded",
                true,
                "hello",
                null,
                null,
                "https://www.douyin.com/user/abc",
                "2026-06-10T16:00:00",
                "2026-06-10T16:02:00");
        when(queryService.leadPool(eq(7L), eq(25), eq("sent"), eq("keyword-x"), eq(null))).thenReturn(List.of(lead));

        var response = controller.leadPool(7L, 25, "sent", "keyword-x", null);

        assertThat(response.getData()).containsExactly(lead);
        verify(queryService).leadPool(eq(7L), eq(25), eq("sent"), eq("keyword-x"), eq(null));
    }

    @Test
    void statsEndpointReturnsWorkspaceAcquisitionDashboard() {
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
        when(queryService.stats(eq(7L), eq(30), eq("keyword-x"))).thenReturn(stats);

        var response = controller.stats(7L, 30, "keyword-x");

        assertThat(response.getData()).isEqualTo(stats);
        verify(queryService).stats(eq(7L), eq(30), eq("keyword-x"));
    }

    @Test
    void templateEndpointsDelegateToTemplateService() {
        UserEntity user = new UserEntity();
        user.setId(9L);
        when(authService.findByUsername("alice")).thenReturn(user);
        DouyinLeadTemplateRequest request = new DouyinLeadTemplateRequest(
                "template-name",
                "keyword-x",
                "most_liked",
                2,
                List.of(CommentMatchRule.keyword("comment-rule")),
                "hello",
                true,
                false);
        DouyinLeadTemplateDTO dto = new DouyinLeadTemplateDTO(
                "1",
                "template-name",
                "keyword-x",
                "most_liked",
                2,
                List.of(CommentMatchRule.keyword("comment-rule")),
                "hello",
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

    // --- Cross-workspace IDOR guards (P0 lead-acquisition isolation) ---

    @Test
    void runEndpointVerifiesRunOwnershipAndBlocksForeignWorkspace() {
        doThrow(new MateClawException("err.common.wrong_workspace", 403, "wrong workspace"))
                .when(queryService).assertRunInWorkspace(7L, 10L);

        assertThatThrownBy(() -> controller.run(10L, 7L))
                .isInstanceOf(MateClawException.class);
        // The data read must never happen once ownership verification fails.
        verify(queryService, never()).byRun(any());
    }

    @Test
    void cancelEndpointVerifiesRunOwnershipBeforeCancelling() {
        doThrow(new MateClawException("err.common.wrong_workspace", 403, "wrong workspace"))
                .when(queryService).assertRunInWorkspace(7L, 10L);

        assertThatThrownBy(() -> controller.cancel(10L, 7L))
                .isInstanceOf(MateClawException.class);
        verify(runService, never()).cancel(any());
    }

    @Test
    void commentsEndpointVerifiesTaskOwnershipAndBlocksForeignWorkspace() {
        doThrow(new MateClawException("err.common.wrong_workspace", 403, "wrong workspace"))
                .when(queryService).assertTaskInWorkspace(7L, 20L);

        assertThatThrownBy(() -> controller.comments(20L, 7L))
                .isInstanceOf(MateClawException.class);
        verify(queryService, never()).comments(any());
    }

    @Test
    void profilesEndpointVerifiesTaskOwnershipBeforeReading() {
        when(queryService.profiles(20L)).thenReturn(List.of());

        controller.profiles(20L, 7L);

        verify(queryService).assertTaskInWorkspace(7L, 20L);
    }

    @Test
    void streamEndpointRejectsRunFromWorkspaceTheUserIsNotMemberOf() {
        when(queryService.runWorkspaceId(10L)).thenReturn(99L);
        UserEntity user = new UserEntity();
        user.setId(9L);
        user.setRole("user");
        when(authService.findByUsername("mallory")).thenReturn(user);
        when(workspaceService.hasPermissionCached(99L, 9L, "viewer")).thenReturn(false);

        assertThatThrownBy(() -> controller.streamEvents(10L, null, null,
                new TestingAuthenticationToken("mallory", "pw")))
                .isInstanceOf(MateClawException.class);
        verify(eventStreamService, never()).stream(any(), any(), any());
    }

    @Test
    void streamEndpointAllowsRunForWorkspaceMember() {
        when(queryService.runWorkspaceId(10L)).thenReturn(99L);
        UserEntity user = new UserEntity();
        user.setId(9L);
        user.setRole("user");
        when(authService.findByUsername("alice")).thenReturn(user);
        when(workspaceService.hasPermissionCached(99L, 9L, "viewer")).thenReturn(true);
        SseEmitter emitter = new SseEmitter();
        when(eventStreamService.stream(eq(10L), any(), any())).thenReturn(emitter);

        SseEmitter response = controller.streamEvents(10L, null, null,
                new TestingAuthenticationToken("alice", "pw"));

        assertThat(response).isSameAs(emitter);
    }
}
