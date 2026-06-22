package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.action.ClickPayload;
import vip.mate.browser.edge.action.ClickSuccess;
import vip.mate.browser.edge.action.CloseTabPayload;
import vip.mate.browser.edge.action.CloseTabSuccess;
import vip.mate.browser.edge.action.MoveMousePayload;
import vip.mate.browser.edge.action.NavigateSuccess;
import vip.mate.browser.edge.action.PressKeyPayload;
import vip.mate.browser.edge.action.PressKeySuccess;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.action.TypePayload;
import vip.mate.browser.edge.action.TypeSuccess;
import vip.mate.browser.edge.action.WaitPayload;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.edge.session.BrowserSessionView;
import vip.mate.browser.orchestrator.ActionPlanner;
import vip.mate.browser.orchestrator.GroundingDispatcher;
import vip.mate.browser.orchestrator.PlanExecutionService;
import vip.mate.browser.orchestrator.PlanResult;
import vip.mate.browser.orchestrator.domain.BBox;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;
import vip.mate.browser.orchestrator.snapshot.PageSnapshotService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link ExtensionBrowserTool}. All Phase 2 services
 * are mocked; we verify that each {@code @Tool} method shapes the right
 * {@link ActionRequest} / {@link GroundingHint} and surfaces the
 * downstream result as the documented JSON.
 *
 * <p>Six tools × happy + edge cases ≈ 9 cases. The grounding-failure
 * fallthrough is the highest-value path because that's how the LLM
 * recovers without crashing the run.
 */
class ExtensionBrowserToolTest {

    private BrowserSessionRegistry registry;
    private ActionPlanner planner;
    private PlanExecutionService planExec;
    private GroundingDispatcher dispatcher;
    private PageSnapshotService snapshotService;
    private ObjectMapper mapper;
    private ExtensionBrowserTool tool;
    private BrowserSession session;

    @BeforeEach
    void setUp() {
        registry = mock(BrowserSessionRegistry.class);
        planner = mock(ActionPlanner.class);
        planExec = mock(PlanExecutionService.class);
        dispatcher = mock(GroundingDispatcher.class);
        snapshotService = mock(PageSnapshotService.class);
        mapper = new ObjectMapper();
        tool = new ExtensionBrowserTool(registry, planner, planExec,
                dispatcher, snapshotService, mapper, "default");
        session = BrowserSession.builder()
                .id("sess-1").subject("default").agentVersion("0.1.0")
                .ws(null).lastHeartbeatAt(java.time.Instant.now()).build();

        // resolveSession now goes through findLiveBySubject + isLive (the
        // activity-checked path). For these dispatch-logic unit tests the session
        // has a null ws, so stub isLive(...) to treat the test session as live;
        // findLiveBySubject mirrors what the old findBySubject stub returned.
        when(registry.findLiveBySubject("default")).thenReturn(Optional.of(session));
        when(registry.isLive(any())).thenReturn(true);
    }

    // -----------------------------------------------------------------
    // Session resolution
    // -----------------------------------------------------------------

    @Test
    void allTools_returnNO_SESSION_whenNoSubjectMatch() throws Exception {
        when(registry.findLiveBySubject("default")).thenReturn(Optional.empty());

        String out = tool.extension_browser_navigate("https://example.com", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("NO_SESSION");
        verify(planExec, never()).execute(any(), any());
    }

    @Test
    void resolveSession_fallsBackToSingleLiveSession_whenSubjectMisses() throws Exception {
        // Real edge sessions register under the authed user's id (e.g. "1"), not
        // the configured "default". With exactly one browser connected the tool
        // should target it rather than fail NO_SESSION.
        when(registry.findLiveBySubject("default")).thenReturn(Optional.empty());
        when(registry.snapshot()).thenReturn(List.of(
                new BrowserSessionView("sess-1", "1", "0.1.0", java.time.Instant.now())));
        when(registry.find("sess-1")).thenReturn(Optional.of(session));
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(new ActionResult.Success(10L,
                        new NavigateSuccess("https://example.com", 200, "load"))))));

        String out = tool.extension_browser_navigate("https://example.com", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();
        verify(planExec).execute(any(), any());
    }

    @Test
    void resolveSession_staysStrict_whenMultipleSessionsAndSubjectMisses() throws Exception {
        // Ambiguous: more than one browser connected and none match the subject.
        // Don't guess which is the user's — noSession() surfaces AMBIGUOUS_SESSION
        // (registry.size() > 1) so the agent tells the user to connect from the
        // right account/device. (Pre-existing assertion drift: this test asserted
        // NO_SESSION while production has long returned AMBIGUOUS_SESSION for the
        // >1-browser case; corrected to match actual behaviour.)
        when(registry.findLiveBySubject("default")).thenReturn(Optional.empty());
        when(registry.size()).thenReturn(2);
        when(registry.snapshot()).thenReturn(List.of(
                new BrowserSessionView("sess-1", "1", "0.1.0", java.time.Instant.now()),
                new BrowserSessionView("sess-2", "2", "0.1.0", java.time.Instant.now())));

        String out = tool.extension_browser_navigate("https://example.com", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("AMBIGUOUS_SESSION");
        verify(planExec, never()).execute(any(), any());
    }

    // -----------------------------------------------------------------
    // browser_navigate
    // -----------------------------------------------------------------

    @Test
    void browserNavigate_dispatchesNavigateActionAndReturnsSuccessJson() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(123L,
                                new NavigateSuccess("https://example.com", 200, "load"))))));

        String out = tool.extension_browser_navigate("https://example.com", "load", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("elapsed_ms").asLong()).isEqualTo(123);
        assertThat(j.get("results").get(0).get("kind").asText()).isEqualTo("navigate");
        assertThat(j.get("results").get(0).get("payload").get("final_url").asText())
                .isEqualTo("https://example.com");

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.NAVIGATE);
        assertThat(sent.get(0).tabRef()).isEqualTo(new TabRef.Main());
    }

    // -----------------------------------------------------------------
    // browser_click
    // -----------------------------------------------------------------

    @Test
    void browserClick_onHit_callsPlannerAndPlanExec() throws Exception {
        var grounded = new GroundedTarget(new BBox(100, 200, 80, 32));
        when(dispatcher.ground(any(), any(), any()))
                .thenReturn(new GroundingResult.Hit(grounded, "data-test=submit"));
        when(planner.plan(any())).thenReturn(List.of(
                navRequest(ActionKind.MOVE_MOUSE),
                navRequest(ActionKind.CLICK)));
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(50L, new ClickSuccess())))));

        String out = tool.extension_browser_click("Submit", "button", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();
        verify(planner).plan(any());
    }

    @Test
    void browserClick_onAmbiguous_returnsGROUNDING_AMBIGUOUS_withoutPlanExec() throws Exception {
        var t1 = new GroundedTarget(new BBox(0, 0, 10, 10));
        var t2 = new GroundedTarget(new BBox(20, 20, 10, 10));
        when(dispatcher.ground(any(), any(), any()))
                .thenReturn(new GroundingResult.Ambiguous(List.of(t1, t2), "two Submits"));

        String out = tool.extension_browser_click("Submit", null, null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("GROUNDING_AMBIGUOUS");
        assertThat(j.get("message").asText()).contains("two Submits");
        verify(planner, never()).plan(any());
        verify(planExec, never()).execute(any(), any());
    }

    @Test
    void browserClick_onMiss_returnsGROUNDING_MISS() throws Exception {
        when(dispatcher.ground(any(), any(), any()))
                .thenReturn(new GroundingResult.Miss("no element matches"));

        String out = tool.extension_browser_click("Nonexistent", null, null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("GROUNDING_MISS");
    }

    @Test
    void browserClick_usesA11yMatchHintWithDefaultRoleButton() throws Exception {
        when(dispatcher.ground(any(), any(), any()))
                .thenReturn(new GroundingResult.Miss("not found"));

        tool.extension_browser_click("Submit", null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(GroundingHint.class);
        verify(dispatcher).ground(any(), any(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(GroundingHint.A11yMatch.class);
        assertThat(((GroundingHint.A11yMatch) captor.getValue()).role()).isEqualTo("button");
    }

    // -----------------------------------------------------------------
    // browser_type / scroll / wait — minimal coverage
    // -----------------------------------------------------------------

    @Test
    void browserType_dispatchesTypeRequest() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L, new TypeSuccess(11))))));

        String out = tool.extension_browser_type("hello world", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("results").get(0).get("payload").get("chars_typed").asInt())
                .isEqualTo(11);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.TYPE);
    }

    @Test
    void browserTypeAt_dispatchesTypeRequestWithFocusTargetForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L, new TypeSuccess(11))))));

        tool.extension_browser_type_at("hello world", new TypePayload.FocusTarget(120.0, 45.0), null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.TYPE);
        var payload = (TypePayload) sent.get(0).params();
        assertThat(payload.focusTarget()).isEqualTo(new TypePayload.FocusTarget(120.0, 45.0));
    }

    @Test
    void browserTypeAtActive_dispatchesTypeRequestToActiveTabForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(2L, new TypeSuccess(2))))));

        tool.extension_browser_type_at_active("你好", null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.TYPE);
        assertThat(sent.get(0).tabRef()).isEqualTo(new TabRef.Active());
    }

    @Test
    void browserPressKey_dispatchesPressKeyRequestForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L, new PressKeySuccess("x"))))));

        String out = tool.extension_browser_press_key("x", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("results").get(0).get("payload").get("key").asText())
                .isEqualTo("x");

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.PRESS_KEY);
        var payload = (PressKeyPayload) sent.get(0).params();
        assertThat(payload.key()).isEqualTo("x");
    }

    @Test
    void serviceCloseTabActive_dispatchesCloseTabRequest() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L, new CloseTabSuccess(42L))))));

        String out = tool.service_close_tab_active();

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("results").get(0).get("payload").get("tabId").asLong())
                .isEqualTo(42L);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.CLOSE_TAB);
        assertThat(sent.get(0).tabRef()).isEqualTo(new TabRef.Active());
        assertThat(sent.get(0).params()).isInstanceOf(CloseTabPayload.class);
    }

    @Test
    void browserClickAt_dispatchesMoveAndClickRequestsForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L, new ClickSuccess())))));

        tool.extension_browser_click_at(1065.0, 41.0, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.MOVE_MOUSE);
        assertThat(sent.get(1).kind()).isEqualTo(ActionKind.CLICK);
        var move = (MoveMousePayload) sent.get(0).params();
        var click = (ClickPayload) sent.get(1).params();
        assertThat(move.x()).isEqualTo(1065.0);
        assertThat(move.y()).isEqualTo(41.0);
        assertThat(click.x()).isEqualTo(1065.0);
        assertThat(click.y()).isEqualTo(41.0);
    }

    @Test
    void browserClickAtActive_dispatchesRequestsToActiveTabForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L, new ClickSuccess())))));

        tool.extension_browser_click_at_active(1065.0, 41.0, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).tabRef()).isEqualTo(new TabRef.Active());
        assertThat(sent.get(1).tabRef()).isEqualTo(new TabRef.Active());
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.MOVE_MOUSE);
        assertThat(sent.get(1).kind()).isEqualTo(ActionKind.CLICK);
    }

    @Test
    void browserHoverAt_dispatchesMoveAndDwellRequestsForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L,
                                new vip.mate.browser.edge.action.MoveMouseSuccess(11L, 3))))));

        tool.extension_browser_hover_at(1065.0, 41.0, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.MOVE_MOUSE);
        assertThat(sent.get(1).kind()).isEqualTo(ActionKind.WAIT);
        var move = (MoveMousePayload) sent.get(0).params();
        var wait = (WaitPayload) sent.get(1).params();
        assertThat(move.x()).isEqualTo(1065.0);
        assertThat(move.y()).isEqualTo(41.0);
        assertThat(wait.strategy()).isEqualTo("time");
        assertThat(wait.durationMs()).isEqualTo(450L);
    }

    @Test
    void browserHoverAtLinear_dispatchesLinearMoveForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L,
                                new vip.mate.browser.edge.action.MoveMouseSuccess(11L, 2))))));

        tool.extension_browser_hover_at_linear(900.0, 360.0, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent).hasSize(2);
        var move = (MoveMousePayload) sent.get(0).params();
        assertThat(move.x()).isEqualTo(900.0);
        assertThat(move.y()).isEqualTo(360.0);
        assertThat(move.profile()).isEqualTo("linear");
    }

    @Test
    void browserClickAtLinear_dispatchesLinearMoveThenClickForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of(
                        new ActionResult.Success(11L,
                                new vip.mate.browser.edge.action.MoveMouseSuccess(11L, 2)),
                        new ActionResult.Success(12L,
                                new vip.mate.browser.edge.action.ClickSuccess())))));

        tool.extension_browser_click_at_linear(775.0, 163.0, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.MOVE_MOUSE);
        assertThat(sent.get(1).kind()).isEqualTo(ActionKind.CLICK);
        var move = (MoveMousePayload) sent.get(0).params();
        assertThat(move.profile()).isEqualTo("linear");
    }

    @Test
    void browserScroll_dispatchesScrollRequest() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of())));

        tool.extension_browser_scroll("down", 800, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.SCROLL);
    }

    @Test
    void browserScrollAt_dispatchesCoordinateScrollRequestForHarnesses() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of())));

        tool.extension_browser_scroll_at("down", 800, 980.0, 360.0, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.SCROLL);
        var payload = (vip.mate.browser.edge.action.ScrollPayload) sent.get(0).params();
        assertThat(payload.x()).isEqualTo(980.0);
        assertThat(payload.y()).isEqualTo(360.0);
    }

    @Test
    void browserWait_dispatchesWaitRequest_timeStrategy() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Success(List.of())));

        tool.extension_browser_wait("time", 500L, null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(planExec).execute(any(), captor.capture());
        @SuppressWarnings("unchecked")
        List<ActionRequest> sent = (List<ActionRequest>) captor.getValue();
        assertThat(sent.get(0).kind()).isEqualTo(ActionKind.WAIT);
    }

    // -----------------------------------------------------------------
    // browser_observe
    // -----------------------------------------------------------------

    @Test
    void browserObserve_returnsSnapshotTreeAsJson() throws Exception {
        var snap = new PageSnapshot("snap-1", 1L, 42L,
                "Button[ref=ref_1]: Submit @{100,200 80x32}",
                new Viewport(1280, 800),
                "https://example.com/page",
                "Example page");
        when(snapshotService.requestFresh(any(), any(), any())).thenReturn(Mono.just(snap));

        String out = tool.extension_browser_observe("interactive", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("snapshot_id").asText()).isEqualTo("snap-1");
        assertThat(j.get("tree").asText()).contains("Submit");
        assertThat(j.get("viewport").get("w").asInt()).isEqualTo(1280);
        // The new shape surfaces the live URL + title as dedicated fields so
        // the LLM can detect navigation between observes (the search-loop fix).
        assertThat(j.get("url").asText()).isEqualTo("https://example.com/page");
        assertThat(j.get("title").asText()).isEqualTo("Example page");
    }

    @Test
    void browserObserveActive_readsActiveTabForHarnesses() throws Exception {
        var snap = new PageSnapshot("snap-1", 1L, 42L,
                "Button[ref=ref_1]: Follow @{100,200 80x32}",
                new Viewport(1280, 800),
                "https://www.douyin.com/user/abc",
                "主页");
        when(snapshotService.requestFresh(any(), any(), any())).thenReturn(Mono.just(snap));

        String out = tool.extension_browser_observe_active("all", null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();
        assertThat(j.get("url").asText()).contains("/user/");
        var tabCaptor = org.mockito.ArgumentCaptor.forClass(TabRef.class);
        verify(snapshotService).requestFresh(any(), tabCaptor.capture(), any());
        assertThat(tabCaptor.getValue()).isEqualTo(new TabRef.Active());
    }

    @Test
    void browserObserve_defaultsFilterToDefault_whenFilterArgIsNull() throws Exception {
        // The default observe filter is 'default' (interactive + landmarks), NOT
        // 'interactive'. The landmark/region context lets the LLM disambiguate
        // SPA pages like Douyin (nav/main/search sections). Capture the filter
        // string passed downstream to lock this contract.
        var snap = new PageSnapshot("snap-1", 1L, 42L,
                "Searchbox[ref=ref_1, frame=0]: 搜索视频 @{640,18 220x36}",
                new Viewport(1280, 800),
                "https://www.douyin.com",
                "抖音");
        when(snapshotService.requestFresh(any(), any(), any())).thenReturn(Mono.just(snap));

        String out = tool.extension_browser_observe(null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isTrue();

        var filterCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(snapshotService).requestFresh(any(), any(), filterCaptor.capture());
        assertThat(filterCaptor.getValue()).isEqualTo("default");
    }

    // -----------------------------------------------------------------
    // Partial plan failure
    // -----------------------------------------------------------------

    @Test
    void planFailureMidStep_returnsPartialWithFailureCode() throws Exception {
        when(planExec.execute(any(), any())).thenReturn(Mono.just((PlanResult)
                new PlanResult.Partial(
                        List.of(new ActionResult.Success(50L, new NavigateSuccess("https://x", 200, "load"))),
                        new ActionResult.Failure("TIMEOUT_PAGE_LOAD", "slow page", true))));

        String out = tool.extension_browser_navigate("https://slow.example", null, null);

        JsonNode j = mapper.readTree(out);
        assertThat(j.get("ok").asBoolean()).isFalse();
        assertThat(j.get("code").asText()).isEqualTo("TIMEOUT_PAGE_LOAD");
        assertThat(j.get("completed_before_failure").asInt()).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static ActionRequest navRequest(ActionKind kind) {
        return switch (kind) {
            case NAVIGATE -> new ActionRequest(UUID.randomUUID().toString(), new TabRef.Main(),
                    ActionKind.NAVIGATE,
                    new vip.mate.browser.edge.action.NavigatePayload("https://x", null, "load"),
                    15_000L);
            case MOVE_MOUSE -> new ActionRequest(UUID.randomUUID().toString(), new TabRef.Main(),
                    ActionKind.MOVE_MOUSE,
                    new vip.mate.browser.edge.action.MoveMousePayload(140, 216, "natural"),
                    15_000L);
            case CLICK -> new ActionRequest(UUID.randomUUID().toString(), new TabRef.Main(),
                    ActionKind.CLICK,
                    new vip.mate.browser.edge.action.ClickPayload(140, 216, "left", 1),
                    15_000L);
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }
}
