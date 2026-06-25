package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.browser.edge.RoutingSubjectContext;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ClickProfileActionPayload;
import vip.mate.browser.edge.action.ClickProfileActionSuccess;
import vip.mate.browser.edge.action.ClickSuccess;
import vip.mate.browser.edge.action.ClickPayload;
import vip.mate.browser.edge.action.DetectRegionPayload;
import vip.mate.browser.edge.action.DetectRegionSuccess;
import vip.mate.browser.edge.action.CloseTabPayload;
import vip.mate.browser.edge.action.CloseTabSuccess;
import vip.mate.browser.edge.action.DouyinCommentNetworkPayload;
import vip.mate.browser.edge.action.DouyinSearchPayload;
import vip.mate.browser.edge.action.DouyinOpenVideoPayload;
import vip.mate.browser.edge.action.DouyinUiPayload;
import vip.mate.browser.edge.action.DouyinCommentNetworkSuccess;
import vip.mate.browser.edge.action.DouyinSearchSuccess;
import vip.mate.browser.edge.action.DouyinOpenVideoSuccess;
import vip.mate.browser.edge.action.DouyinUiSuccess;
import vip.mate.browser.edge.action.ExtractRegionPayload;
import vip.mate.browser.edge.action.ExtractRegionSuccess;
import vip.mate.browser.edge.action.MoveMousePayload;
import vip.mate.browser.edge.action.MoveMouseSuccess;
import vip.mate.browser.edge.action.NavigatePayload;
import vip.mate.browser.edge.action.NavigateSuccess;
import vip.mate.browser.edge.action.OpenAuthorFromCommentPayload;
import vip.mate.browser.edge.action.OpenAuthorFromCommentSuccess;
import vip.mate.browser.edge.action.PressKeyPayload;
import vip.mate.browser.edge.action.RegisterRegionPayload;
import vip.mate.browser.edge.action.RegisterRegionSuccess;
import vip.mate.browser.edge.action.ScrollPayload;
import vip.mate.browser.edge.action.ScrollRegionPayload;
import vip.mate.browser.edge.action.ScrollRegionSuccess;
import vip.mate.browser.edge.action.ScrollSuccess;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.action.TypeDmDraftPayload;
import vip.mate.browser.edge.action.TypeDmDraftSuccess;
import vip.mate.browser.edge.action.TypePayload;
import vip.mate.browser.edge.action.TypeSuccess;
import vip.mate.browser.edge.action.WaitPayload;
import vip.mate.browser.edge.action.WaitSuccess;
import vip.mate.browser.edge.action.ActionResult.Success;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.orchestrator.ActionPlanner;
import vip.mate.browser.orchestrator.GroundingDispatcher;
import vip.mate.browser.orchestrator.PlanExecutionService;
import vip.mate.browser.orchestrator.PlanResult;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.Step;
import vip.mate.browser.orchestrator.snapshot.DefaultSnapshotEdgeClient.SnapshotFailureException;
import vip.mate.browser.orchestrator.snapshot.PageSnapshotService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Browser-control tool that targets the <strong>user's real Chrome window</strong>
 * via the Phase 1/2/2.1 extension stack. Coexists with {@link BrowserUseTool}
 * (which drives a server-side Playwright headless browser).
 *
 * <p><strong>When to use this vs {@code BrowserUseTool}:</strong>
 * <ul>
 *   <li>Use {@code extension_browser_*} (this tool family) when the user wants
 *       to see the agent operating in their own browser — for example, "open
 *       this tab and click Submit" — or when authenticated state in the user's
 *       browser matters.</li>
 *   <li>Use {@code browser_use} (the Playwright tool) for headless scraping,
 *       background automation, or when no user browser is connected.</li>
 * </ul>
 *
 * <p><strong>Six fine-grained {@code @Tool} methods</strong> (not one fat
 * action-enum tool) so the LLM sees one clear contract per action. The
 * coordinate-free hint-text contract (click "Submit" near "Comments")
 * goes through {@link GroundingDispatcher} → DOM/A11y/Vision engines;
 * the {@link ActionPlanner} then turns the resolved target into the
 * pair of {@code move_mouse}+{@code click} action requests, which
 * {@link PlanExecutionService} runs sequentially via {@code concatMap}.
 *
 * <p>Single-session-per-tenant model for Phase 3: tools look up the
 * {@link BrowserSession} by a configurable subject
 * ({@code mateclaw.browser.tool-subject}, default {@code default}). Phase 4
 * will resolve the subject from {@link ToolContext} (per-conversation auth
 * principal); the {@code @Nullable ToolContext} param is already on every
 * method to keep the signature stable across that future change.
 *
 * <p>browser_screenshot is intentionally NOT in this first-pass tool family
 * — Phase 3 T3.2-protocol adds the {@code screenshot.capture.*} wire
 * kinds; the Vision engine (T3.2 real impl) will consume them. The
 * stand-alone screenshot tool ships as a follow-up commit.
 */
@Slf4j
@Component
public class ExtensionBrowserTool {

    /** Default action deadline. Tunable per-call via the {@code deadline_ms} arg. */
    private static final long DEFAULT_DEADLINE_MS = 15_000L;

    /**
     * Deadline for slow side-effecting DM / follow actions (type_dm_draft / send_dm /
     * click_profile_action). 外层 deadline 必须 > handler 内部最坏预算（DM ≈20s：
     * ensureRendered 12s + 发送重试 ~8s），否则结构性超时——慢电脑上私信会在确认轮询完成前
     * 被截断而发不出去。35s 留足余量。该值三层共用（后端 completeOnDeadline、扩展
     * runWithDeadline、handler 内部确认轮询），handler 内部所有等待都须在此预算内完成。
     */
    private static final long DM_DEADLINE_MS = 35_000L;

    private final BrowserSessionRegistry registry;
    private final ActionPlanner planner;
    private final PlanExecutionService planExec;
    private final GroundingDispatcher dispatcher;
    private final PageSnapshotService snapshotService;
    private final ObjectMapper mapper;

    /**
     * Fallback subject under which a single-tenant deployment's extension
     * session may be registered. Per-user routing now derives the real subject
     * from {@link ToolContext} (see {@link #resolveSession(ToolContext)}); this
     * is only consulted when the context yields no subject.
     */
    private final String defaultSubject;

    public ExtensionBrowserTool(BrowserSessionRegistry registry,
                                ActionPlanner planner,
                                PlanExecutionService planExec,
                                GroundingDispatcher dispatcher,
                                PageSnapshotService snapshotService,
                                ObjectMapper mapper,
                                @Value("${mateclaw.browser.tool-subject:default}") String defaultSubject) {
        this.registry = registry;
        this.planner = planner;
        this.planExec = planExec;
        this.dispatcher = dispatcher;
        this.snapshotService = snapshotService;
        this.mapper = mapper;
        this.defaultSubject = defaultSubject;
    }

    // -----------------------------------------------------------------
    // browser_navigate
    // -----------------------------------------------------------------

    @Tool(description = """
            Navigate the USER'S OWN visible Chrome window (the browser they're watching) to a URL.
            ★ This is the tool to use whenever the user says "my/this/the browser" (用我的浏览器 /
            这个浏览器 / 打开浏览器) or wants to see the page open in front of them. Prefer this over
            `browser_use` (which is a hidden server-side headless browser) for anything involving the
            user's real browser. Then read the page with extension_browser_observe and act with
            extension_browser_click / _type / _scroll.

            Returns a JSON object:
              { "ok": true, "final_url": "...", "load_state": "load" }
            or on failure:
              { "ok": false, "code": "NO_SESSION|NO_TARGET_TAB|TIMEOUT_PAGE_LOAD|...", "message": "..." }
            """)
    public String extension_browser_navigate(
            @ToolParam(description = "URL to navigate to, including scheme (http:// or https://)") String url,
            @ToolParam(description = "Wait strategy: 'load' (default) | 'domcontentloaded' | 'network_idle' | 'none'",
                       required = false) String waitFor,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                new TabRef.Main(),
                ActionKind.NAVIGATE,
                new NavigatePayload(url, null, defaulted(waitFor, "load")),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(req));
    }

    // -----------------------------------------------------------------
    // browser_click
    // -----------------------------------------------------------------

    @Tool(description = """
            Click an element on the page identified by its accessible role + visible text.
            The hint_text is matched as a CASE-INSENSITIVE SUBSTRING against the element's
            accessible name (button label, link text, etc.). For an unlabeled input — a
            search box or text field — that accessible name is the element's PLACEHOLDER
            text, so to click a field whose placeholder is "搜索视频 / Search videos" pass
            hint_text "搜索" or "search".

            Choosing the role:
              - role="searchbox" for a site/page SEARCH input (Douyin/YouTube-style search box).
              - role="textbox" for a generic text field (login, comment, form input).
              - role="button" (default) | "link" | "menuitem" | "tab" | "checkbox" otherwise.
            Read the element's Role and accessible name straight from
            extension_browser_observe's tree, then pass them here. Example — to click
            Douyin's search field: role="searchbox", hint_text="搜索" (or "search").

            near_label is an optional containing-section hint that disambiguates when the
            same hint_text appears multiple times (for example, two "Like" buttons in
            different sections — pass near_label="Comments" to pick the one inside the
            Comments section). Powered by Phase 3 T3.1 A11yEngine.

            ★ TOGGLES: a control that opens a menu / dropdown / filter panel (筛选, 排序, a
            "more" menu) CLOSES it when clicked again. After clicking one to open it, do NOT
            click it a second time — call extension_browser_observe to read the now-open panel
            and click the option you want INSIDE it. Re-clicking the toggle is what closes it.

            ★ VISIBLE BUT NOT IN THE TREE: if you can SEE a target on the page (a filter option,
            a custom widget) but it isn't a Button/Link/etc. in the observe tree, STILL call this
            tool with its visible text — grounding falls back to a screenshot + vision pass that
            can locate it. Don't refuse just because it's not a "standard" element.

            Returns a JSON object:
              { "ok": true, "elapsed_ms": 123 }
            or on grounding failure:
              { "ok": false, "code": "GROUNDING_AMBIGUOUS|GROUNDING_MISS|NO_SESSION", "message": "..." }
            """)
    public String extension_browser_click(
            @ToolParam(description = "Accessible name (matched case-insensitively as a substring) of the element to click. For buttons/links it's the visible label ('Submit', 'Cancel', 'Comments'); for an unlabeled search box or text field it's the placeholder text ('搜索', 'Search videos').")
            String hintText,
            @ToolParam(description = "Optional accessible role hint: 'button' (default) | 'link' | 'menuitem' | 'tab' | 'checkbox' | 'searchbox' (a site/page search input) | 'textbox' (a generic text field)",
                       required = false) String role,
            @ToolParam(description = "Optional containing-section heading text — used by the A11y engine to disambiguate when hint_text matches multiple elements. Provide the visible text of the nearest enclosing heading/section/landmark/article/region (e.g. 'Comments', 'Search results').",
                       required = false) String nearLabel,
            @Nullable ToolContext ctx) {
        return extension_browser_click_tab(new TabRef.Main(), hintText, role, nearLabel, ctx);
    }

    private String extension_browser_click_tab(TabRef tabRef,
                                               String hintText,
                                               String role,
                                               @Nullable String nearLabel,
                                               @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        String resolvedRole = defaulted(role, "button");
        GroundingHint hint = new GroundingHint.A11yMatch(
                resolvedRole,
                Pattern.compile(Pattern.quote(hintText), Pattern.CASE_INSENSITIVE),
                "interactive",
                emptyToNull(nearLabel));

        GroundingResult ground = dispatcher.ground(session, tabRef, hint);
        return switch (ground) {
            case GroundingResult.Hit hit -> executePlan(session,
                    planner.plan(new Step.ClickStep(tabRef, ground)));
            case GroundingResult.Ambiguous a -> error("GROUNDING_AMBIGUOUS",
                    "found " + a.candidates().size() + " candidates: " + a.evidence()
                            + " — try refining with a near_label");
            case GroundingResult.Miss m -> error("GROUNDING_MISS", m.reason());
        };
    }

    // -----------------------------------------------------------------
    // browser_hover
    // -----------------------------------------------------------------

    @Tool(description = """
            Move the mouse ONTO an element WITHOUT clicking — to open a HOVER-triggered
            menu / panel and keep it open. Many sites reveal a panel only WHILE the mouse
            rests on a trigger and remove it from the DOM the instant the mouse leaves —
            e.g. Douyin's 筛选 sort panel (排序依据 / 最多点赞 / 最新发布 / 发布时间). A
            CLICK does not reliably open these (and moving away closes them); a hover does.

            Canonical flow for a hover menu:
              extension_browser_hover(hint_text="筛选")        ← panel opens, cursor parked on it
              extension_browser_observe(...)                    ← the panel is now in the tree
              extension_browser_click(hint_text="最多点赞")     ← select the option
            The panel stays open while the cursor rests on the trigger, so observe + the
            follow-up click see it. Do NOT click the trigger to open a hover menu, and do
            NOT move the mouse elsewhere before clicking the option.

            Same role + hint_text + near_label matching as extension_browser_click. The
            cursor is left ON the element (a ~400ms dwell lets the panel render before this
            returns).

            Returns: { "ok": true, ... } or { "ok": false, "code": "GROUNDING_MISS|GROUNDING_AMBIGUOUS|NO_SESSION", ... }
            """)
    public String extension_browser_hover(
            @ToolParam(description = "Accessible name (case-insensitive substring) of the element to hover over, e.g. '筛选'. Read it from extension_browser_observe.")
            String hintText,
            @ToolParam(description = "Optional role hint: 'button' (default) | 'link' | 'menuitem' | 'tab' | 'searchbox' | 'textbox'",
                       required = false) String role,
            @ToolParam(description = "Optional containing-section heading text to disambiguate when hint_text matches multiple elements.",
                       required = false) String nearLabel,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        String resolvedRole = defaulted(role, "button");
        GroundingHint hint = new GroundingHint.A11yMatch(
                resolvedRole,
                Pattern.compile(Pattern.quote(hintText), Pattern.CASE_INSENSITIVE),
                "interactive",
                emptyToNull(nearLabel));

        GroundingResult ground = dispatcher.ground(session, new TabRef.Main(), hint);
        return switch (ground) {
            case GroundingResult.Hit hit -> {
                var center = hit.target().bbox().center();
                // Move the (real) cursor onto the element and STOP — no press. This
                // fires mouseover/mouseenter so a hover-triggered panel opens, and the
                // synthetic pointer stays parked there (CDP keeps the last-moved
                // position), so the panel persists for the next observe + click. The
                // trailing WAIT gives the panel ~400ms to render before we return.
                ActionRequest move = new ActionRequest(
                        newMsgId(), new TabRef.Main(), ActionKind.MOVE_MOUSE,
                        new MoveMousePayload(center.x(), center.y(), "natural"), DEFAULT_DEADLINE_MS);
                ActionRequest dwell = new ActionRequest(
                        newMsgId(), new TabRef.Main(), ActionKind.WAIT,
                        new WaitPayload("time", 400L, null, null), DEFAULT_DEADLINE_MS);
                yield executePlan(session, List.of(move, dwell));
            }
            case GroundingResult.Ambiguous a -> error("GROUNDING_AMBIGUOUS",
                    "found " + a.candidates().size() + " candidates: " + a.evidence()
                            + " — try refining with a near_label");
            case GroundingResult.Miss m -> error("GROUNDING_MISS", m.reason());
        };
    }

    // -----------------------------------------------------------------
    // browser_type
    // -----------------------------------------------------------------

    @Tool(description = """
            Type text into the currently focused element. To type into a specific field,
            click that field first via extension_browser_click, then call this tool. The
            implementation does NOT auto-focus — that's deliberate, to avoid clobbering an
            existing focus the user established.

            To target a search box or text field, first click it with the right role:
            role="searchbox" for a site/page search input (e.g. Douyin's search box,
            hint_text "搜索" / "search"), role="textbox" for a generic field. The hint_text
            is matched case-insensitively as a substring of the element's accessible name,
            which for an unlabeled input is its PLACEHOLDER text.

            ★ TO SUBMIT A SEARCH / FORM: end your text with a newline only when the user did
            not ask for a visible submit click. If the user explicitly says to click the search
            button, type plain text without "\\n", then click the visible search button.
            After typing, call extension_browser_observe and confirm the submit worked by its
            EFFECT, not by one fixed signal: results / new content appeared, OR the `url` /
            `title` changed, OR the field now holds your text. Many sites (especially SPAs)
            render results IN PLACE with the url UNCHANGED — do NOT treat an unchanged url as
            failure. If nothing changed at all, retry once, then click the visible search
            button instead; never repeat the same action in a loop.

            Returns a JSON object on success:
              { "ok": true, "chars_typed": 5 }
            """)
    public String extension_browser_type(
            @ToolParam(description = "Text to type. Unicode supported. End with \\n to press Enter (submits a search/form).")
            String text,
            @Nullable ToolContext ctx) {
        return extension_browser_type_at(text, null, ctx);
    }

    String extension_browser_type_at(String text,
                                     @Nullable TypePayload.FocusTarget focusTarget,
                                     @Nullable ToolContext ctx) {
        return extension_browser_type_at_tab(new TabRef.Main(), text, focusTarget, ctx);
    }

    String extension_browser_type_at_active(String text,
                                            @Nullable TypePayload.FocusTarget focusTarget,
                                            @Nullable ToolContext ctx) {
        return extension_browser_type_at_tab(new TabRef.Active(), text, focusTarget, ctx);
    }

    private String extension_browser_type_at_tab(TabRef tabRef,
                                                 String text,
                                                 @Nullable TypePayload.FocusTarget focusTarget,
                                                 @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.TYPE,
                new TypePayload(text, focusTarget),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(req));
    }

    String extension_browser_press_key(String key, @Nullable ToolContext ctx) {
        return extension_browser_press_key_at_tab(new TabRef.Main(), key, ctx);
    }

    String extension_browser_press_key_at_tab(TabRef tabRef, String key, @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.PRESS_KEY,
                new PressKeyPayload(key),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(req));
    }

    String extension_browser_click_at(double x, double y, @Nullable ToolContext ctx) {
        return extension_browser_click_at_tab(new TabRef.Main(), x, y, "natural", ctx);
    }

    String extension_browser_click_at_active(double x, double y, @Nullable ToolContext ctx) {
        return extension_browser_click_at_tab(new TabRef.Active(), x, y, "natural", ctx);
    }

    String extension_browser_click_at_linear(double x, double y, @Nullable ToolContext ctx) {
        return extension_browser_click_at_tab(new TabRef.Main(), x, y, "linear", ctx);
    }

    String extension_browser_click_at_tab(TabRef tabRef,
                                          double x,
                                          double y,
                                          String profile,
                                          @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        ActionRequest move = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.MOVE_MOUSE,
                new MoveMousePayload(x, y, profile),
                DEFAULT_DEADLINE_MS);
        ActionRequest click = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.CLICK,
                new ClickPayload(x, y, "left", 1),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(move, click));
    }

    String extension_browser_hover_at(double x, double y, @Nullable ToolContext ctx) {
        return extension_browser_hover_at(x, y, "natural", ctx);
    }

    String extension_browser_hover_at_linear(double x, double y, @Nullable ToolContext ctx) {
        return extension_browser_hover_at_tab(new TabRef.Main(), x, y, "linear", ctx);
    }

    private String extension_browser_hover_at(double x,
                                              double y,
                                              String profile,
                                              @Nullable ToolContext ctx) {
        return extension_browser_hover_at_tab(new TabRef.Main(), x, y, profile, ctx);
    }

    String extension_browser_hover_at_active_linear(double x, double y, @Nullable ToolContext ctx) {
        return extension_browser_hover_at_tab(new TabRef.Active(), x, y, "linear", ctx);
    }

    private String extension_browser_hover_at_tab(TabRef tabRef,
                                                  double x,
                                                  double y,
                                                  String profile,
                                                  @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        ActionRequest move = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.MOVE_MOUSE,
                new MoveMousePayload(x, y, profile),
                DEFAULT_DEADLINE_MS);
        ActionRequest dwell = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.WAIT,
                new WaitPayload("time", 450L, null, null),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(move, dwell));
    }

    // -----------------------------------------------------------------
    // browser_scroll
    // -----------------------------------------------------------------

    @Tool(description = """
            Scroll the page. Direction is one of up/down/left/right. distance_px is the
            total scroll amount; the underlying CDP implementation segments it into N
            wheel events with log-normal inter-segment delays (mimics human wheel push).

            Returns: { "ok": true }
            """)
    public String extension_browser_scroll(
            @ToolParam(description = "Scroll direction: 'up' | 'down' | 'left' | 'right'") String direction,
            @ToolParam(description = "Total scroll distance in CSS pixels. Default 600.", required = false)
            Integer distancePx,
            @Nullable ToolContext ctx) {
        return extension_browser_scroll_at(direction, distancePx, null, null, ctx);
    }

    String extension_browser_scroll_at(String direction,
                                       Integer distancePx,
                                       @Nullable Double x,
                                       @Nullable Double y,
                                       @Nullable ToolContext ctx) {
        return extension_browser_scroll_at_tab(new TabRef.Main(), direction, distancePx, x, y, ctx);
    }

    String extension_browser_scroll_at_active(String direction,
                                              Integer distancePx,
                                              @Nullable Double x,
                                              @Nullable Double y,
                                              @Nullable ToolContext ctx) {
        return extension_browser_scroll_at_tab(new TabRef.Active(), direction, distancePx, x, y, ctx);
    }

    private String extension_browser_scroll_at_tab(TabRef tabRef,
                                                   String direction,
                                                   Integer distancePx,
                                                   @Nullable Double x,
                                                   @Nullable Double y,
                                                   @Nullable ToolContext ctx) {
        return extension_browser_scroll_at_tab(tabRef, direction, distancePx, x, y, ctx, DEFAULT_DEADLINE_MS);
    }

    private String extension_browser_scroll_at_tab(TabRef tabRef,
                                                   String direction,
                                                   Integer distancePx,
                                                   @Nullable Double x,
                                                   @Nullable Double y,
                                                   @Nullable ToolContext ctx,
                                                   long deadlineMs) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.SCROLL,
                new ScrollPayload(direction, distancePx == null ? 600 : distancePx, 5, x, y),
                deadlineMs);

        return executePlan(session, List.of(req));
    }

    @Tool(description = """
            Scroll a previously detected browser region, such as a Douyin comments panel.
            Prefer this over generic page scroll when collecting comments or working inside
            side panels: the extension keeps the wheel safe point inside the registered
            region and avoids drifting onto the video area or another tab.

            region_key examples: douyin.comments, douyin.profile, dm.compose.
            stop_when.type may be edge, selector_visible, or text_visible.

            Returns: { "ok": true, "results": [{ "kind": "scroll_region", ... }] }
            """)
    public String extension_browser_scroll_region(
            @ToolParam(description = "Registered region key, e.g. 'douyin.comments'") String regionKey,
            @ToolParam(description = "Scroll direction: 'up' | 'down' | 'left' | 'right'") String direction,
            @ToolParam(description = "Scroll amount in CSS pixels. Default 600.", required = false)
            Double amount,
            @ToolParam(description = "Stop predicate type: edge | selector_visible | text_visible", required = false)
            String stopWhenType,
            @ToolParam(description = "Selector for stop_when.type=selector_visible", required = false)
            String selector,
            @ToolParam(description = "Text for stop_when.type=text_visible", required = false)
            String text,
            @Nullable ToolContext ctx) {
        return extension_browser_scroll_region_at_tab(new TabRef.Main(), regionKey, direction, amount,
                stopWhenType, selector, text, ctx);
    }

    public String extension_browser_scroll_region_at_tab(
            TabRef tabRef,
            String regionKey,
            String direction,
            Double amount,
            String stopWhenType,
            String selector,
            String text,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        ScrollRegionPayload.StopWhen stopWhen = null;
        if (stopWhenType != null && !stopWhenType.isBlank()) {
            stopWhen = new ScrollRegionPayload.StopWhen(stopWhenType, emptyToNull(selector), emptyToNull(text));
        }
        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.SCROLL_REGION,
                new ScrollRegionPayload(regionKey, direction, amount == null ? 600.0 : amount, stopWhen, 5),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_scroll_region_main(String regionKey, String direction, double amount, long deadlineMs) {
        return service_scroll_region(new TabRef.Main(), regionKey, direction, amount, deadlineMs);
    }

    public String service_scroll_region_active(String regionKey, String direction, double amount, long deadlineMs) {
        return service_scroll_region(new TabRef.Active(), regionKey, direction, amount, deadlineMs);
    }

    private String service_scroll_region(
            TabRef tabRef,
            String regionKey,
            String direction,
            double amount,
            long deadlineMs) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.SCROLL_REGION,
                new ScrollRegionPayload(regionKey, direction, amount, null, 1),
                Math.max(1_000L, deadlineMs));
        return executePlan(session, List.of(req));
    }

    // -----------------------------------------------------------------
    // browser_wait
    // -----------------------------------------------------------------

    @Tool(description = """
            Pause the agent until a wait condition is satisfied. Use this before an action
            that depends on a page reaching a stable state — for example, wait_for=network_idle
            after a click that triggers a long XHR.

            Strategies:
              - time: sleep duration_ms (deterministic; default if duration_ms is set)
              - load_state: wait for chrome.webNavigation onCompleted matching load_state
                            ('load' | 'domcontentloaded' | 'network_idle')
              - network_idle: Phase-2 fallback — sleeps idle_threshold_ms (default 500ms)

            Returns: { "ok": true, "waited_ms": 500 }
            """)
    public String extension_browser_wait(
            @ToolParam(description = "Wait strategy: 'time' | 'load_state' | 'network_idle'") String strategy,
            @ToolParam(description = "Duration in milliseconds (for strategy=time)", required = false)
            Long durationMs,
            @ToolParam(description = "load_state target: 'load' | 'domcontentloaded' | 'network_idle' (for strategy=load_state)",
                       required = false) String loadState,
            @ToolParam(description = "Idle threshold in milliseconds (for strategy=network_idle, default 500)",
                       required = false) Long idleThresholdMs,
            @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                new TabRef.Main(),
                ActionKind.WAIT,
                new WaitPayload(strategy, durationMs, idleThresholdMs, loadState),
                DEFAULT_DEADLINE_MS);

        return executePlan(session, List.of(req));
    }

    // -----------------------------------------------------------------
    // browser_observe
    // -----------------------------------------------------------------

    @Tool(description = """
            Read the page's accessibility tree as plain text. Use this between actions
            to let the LLM see what's on the page before deciding the next click/type
            target. The returned tree is the same input the A11y engine uses for
            grounding, so referring to "the Submit button near the Comments heading"
            in your next extension_browser_click matches what the page actually exposes.

            The tree format per line is:
              Role[ref=ref_N, frame=0]: accessible name @{x,y wxh}
            for example:
              Searchbox[ref=ref_1, frame=0]: 搜索视频 @{640,18 220x36}
            The text after the colon is the element's ACCESSIBLE NAME. For unlabeled
            inputs (search boxes, text fields) that name is usually the element's
            PLACEHOLDER text. Read it and use it verbatim as the hint_text of your next
            extension_browser_click / _type call — and use the Role token (e.g.
            Searchbox, Textbox, Button, Link) as that call's role argument.

            Typical flow for this tool family:
              navigate → observe (read the tree + url/title) → click (by role + hint_text
              taken from the tree) → type → observe again (confirm the page changed — new
              content appeared, or url/title changed).
            Always observe once after navigating and again after a click/type so you act
            on the page's real, current structure instead of guessing.

            ★ LOGIN / VERIFICATION WALLS: if the tree shows a login or verification
            modal — lines containing 登录 / 扫码登录 / 验证码登录 / 手机号登录 / 登录后 /
            "Sign in" / "Log in" / a QR-code login panel — the site REQUIRES the user to
            be logged in (e.g. Douyin gates search behind login). DO NOT keep retrying the
            same action: that wastes steps and loops. STOP and tell the user, in plain
            language, that the site needs them to log in (in this same browser) and then
            re-run the task. You cannot log in for them. The same applies to CAPTCHA /
            human-verification challenges — surface them, never attempt to solve them.

            Returns a JSON object:
              { "ok": true,
                "snapshot_id": "...",
                "url": "https://...",         ← current page URL. ONE signal of change, not the only one — compare between observes, but many sites/SPAs update content with the url UNCHANGED, so also check `title` and whether goal-relevant content appeared in the tree
                "title": "Page title",
                "viewport": {"w": 1280, "h": 800},
                "tree": "Button[ref=ref_1, frame=0]: Submit @{100,200 80x32}\\n..." }
            """)
    public String extension_browser_observe(
            @ToolParam(description = "Snapshot filter: 'default' (default — interactive elements + landmarks/regions, so you get nav/main/search section context to disambiguate; needed for SPA pages like Douyin) | 'interactive' (buttons/links/inputs only) | 'all'",
                       required = false) String filter,
            @Nullable ToolContext ctx) {
        return extension_browser_observe_tab(new TabRef.Main(), filter, ctx);
    }

    String extension_browser_observe_active(String filter, @Nullable ToolContext ctx) {
        return extension_browser_observe_tab(new TabRef.Active(), filter, ctx);
    }

    public String service_observe_active(String filter) {
        return extension_browser_observe_tab(new TabRef.Active(), filter, null);
    }

    public String service_observe_tab(long tabId, String filter) {
        return extension_browser_observe_tab(new TabRef.Explicit(tabId), filter, null);
    }

    public String service_observe_main(String filter) {
        return extension_browser_observe_tab(new TabRef.Main(), filter, null);
    }

    public String service_click_main(double x, double y) {
        return extension_browser_click_at_tab(new TabRef.Main(), x, y, "linear", null);
    }

    public String service_hover_main(double x, double y) {
        return extension_browser_hover_at_tab(new TabRef.Main(), x, y, "linear", null);
    }

    public String service_hover_text_main(String hintText, String role, @Nullable String nearLabel) {
        return extension_browser_hover(hintText, role, nearLabel, null);
    }

    public String service_click_text_main(String hintText, String role, @Nullable String nearLabel) {
        return extension_browser_click(hintText, role, nearLabel, null);
    }

    public String service_click_text_active(String hintText, String role, @Nullable String nearLabel) {
        return extension_browser_click_tab(new TabRef.Active(), hintText, role, nearLabel, null);
    }

    public String service_click_active(double x, double y) {
        return extension_browser_click_at_tab(new TabRef.Active(), x, y, "linear", null);
    }

    public String service_click_tab(long tabId, double x, double y) {
        return extension_browser_click_at_tab(new TabRef.Explicit(tabId), x, y, "linear", null);
    }

    public String service_hover_active(double x, double y) {
        return extension_browser_hover_at_tab(new TabRef.Active(), x, y, "linear", null);
    }

    public String service_type_active(String text, @Nullable TypePayload.FocusTarget focusTarget) {
        return extension_browser_type_at_tab(new TabRef.Active(), text, focusTarget, null);
    }

    public String service_type_tab(long tabId, String text, @Nullable TypePayload.FocusTarget focusTarget) {
        return extension_browser_type_at_tab(new TabRef.Explicit(tabId), text, focusTarget, null);
    }

    public String service_type_main(String text, @Nullable TypePayload.FocusTarget focusTarget) {
        return extension_browser_type_at_tab(new TabRef.Main(), text, focusTarget, null);
    }

    public String service_press_key_main(String key) {
        return extension_browser_press_key(key, null);
    }

    public String service_press_key_active(String key) {
        return extension_browser_press_key_at_tab(new TabRef.Active(), key, null);
    }

    public String service_press_key_tab(long tabId, String key) {
        return extension_browser_press_key_at_tab(new TabRef.Explicit(tabId), key, null);
    }

    public String service_close_tab_active() {
        return service_close_tab(new TabRef.Active());
    }

    public String service_close_tab(long tabId) {
        return service_close_tab(new TabRef.Explicit(tabId));
    }

    private String service_close_tab(TabRef tabRef) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.CLOSE_TAB,
                new CloseTabPayload(),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_scroll_region_main(String regionKey, String direction, double amount) {
        return extension_browser_scroll_region(regionKey, direction, amount, null, null, null, null);
    }

    public String service_scroll_region_active(String regionKey, String direction, double amount) {
        return extension_browser_scroll_region_at_tab(new TabRef.Active(), regionKey, direction, amount, null, null, null, null);
    }

    public String service_scroll_main(String direction, double distancePx, double x, double y) {
        return extension_browser_scroll_at_tab(new TabRef.Main(), direction, (int) Math.round(distancePx), x, y, null);
    }

    public String service_scroll_active(String direction, double distancePx, double x, double y) {
        return extension_browser_scroll_at_tab(new TabRef.Active(), direction, (int) Math.round(distancePx), x, y, null);
    }

    public String service_scroll_main(String direction, double distancePx, double x, double y, long deadlineMs) {
        return extension_browser_scroll_at_tab(
                new TabRef.Main(), direction, (int) Math.round(distancePx), x, y, null, deadlineMs);
    }

    public String service_scroll_active(String direction, double distancePx, double x, double y, long deadlineMs) {
        return extension_browser_scroll_at_tab(
                new TabRef.Active(), direction, (int) Math.round(distancePx), x, y, null, deadlineMs);
    }

    public String service_register_region_main(String regionKey, double x, double y,
                                               double width, double height, String source) {
        return service_register_region(new TabRef.Main(), regionKey, x, y, width, height, source);
    }

    public String service_register_region_active(String regionKey, double x, double y,
                                                 double width, double height, String source) {
        return service_register_region(new TabRef.Active(), regionKey, x, y, width, height, source);
    }

    public String service_register_region_tab(long tabId, String regionKey, double x, double y,
                                              double width, double height, String source) {
        return service_register_region(new TabRef.Explicit(tabId), regionKey, x, y, width, height, source);
    }

    private String service_register_region(TabRef tabRef, String regionKey, double x, double y,
                                           double width, double height, String source) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.REGISTER_REGION,
                new RegisterRegionPayload(regionKey, new RegisterRegionPayload.Rect(x, y, width, height), source),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_detect_region_main(String regionKey, String strategy) {
        return service_detect_region(new TabRef.Main(), regionKey, strategy);
    }

    public String service_detect_region_active(String regionKey, String strategy) {
        return service_detect_region(new TabRef.Active(), regionKey, strategy);
    }

    public String service_detect_region_tab(long tabId, String regionKey, String strategy) {
        return service_detect_region(new TabRef.Explicit(tabId), regionKey, strategy);
    }

    private String service_detect_region(TabRef tabRef, String regionKey, String strategy) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.DETECT_REGION,
                new DetectRegionPayload(regionKey, strategy),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_extract_region_main(String regionKey, int maxItems) {
        return service_extract_region(new TabRef.Main(), regionKey, maxItems, 0);
    }

    public String service_extract_region_main(String regionKey, int maxItems, int startIndex) {
        return service_extract_region(new TabRef.Main(), regionKey, maxItems, startIndex);
    }

    public String service_extract_region_active(String regionKey, int maxItems) {
        return service_extract_region(new TabRef.Active(), regionKey, maxItems, 0);
    }

    public String service_extract_region_tab(long tabId, String regionKey, int maxItems) {
        return service_extract_region(new TabRef.Explicit(tabId), regionKey, maxItems, 0);
    }

    private String service_extract_region(TabRef tabRef, String regionKey, int maxItems, int startIndex) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.EXTRACT_REGION,
                new ExtractRegionPayload(regionKey, maxItems, startIndex),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_open_author_from_comment_main(String commentText, String authorName) {
        return service_open_author_from_comment_main(commentText, authorName, "");
    }

    public String service_open_author_from_comment_main(String commentText, String authorName, String authorProfileUrl) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                new TabRef.Main(),
                ActionKind.OPEN_AUTHOR_FROM_COMMENT,
                new OpenAuthorFromCommentPayload(commentText, authorName, authorProfileUrl),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_click_profile_action_active(List<String> labels) {
        return service_click_profile_action(new TabRef.Active(), labels);
    }

    public String service_click_profile_action_tab(long tabId, List<String> labels) {
        return service_click_profile_action(new TabRef.Explicit(tabId), labels);
    }

    private String service_click_profile_action(TabRef tabRef, List<String> labels) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.CLICK_PROFILE_ACTION,
                new ClickProfileActionPayload(labels),
                DM_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_douyin_search_main(String keyword) {
        return service_douyin_search(new TabRef.Main(), keyword);
    }

    public String service_douyin_search_tab(long tabId, String keyword) {
        return service_douyin_search(new TabRef.Explicit(tabId), keyword);
    }

    private String service_douyin_search(TabRef tabRef, String keyword) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.DOUYIN_SEARCH,
                new DouyinSearchPayload(keyword),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_douyin_open_video_main(int index) {
        return service_douyin_open_video(new TabRef.Main(), index);
    }

    public String service_douyin_open_video_tab(long tabId, int index) {
        return service_douyin_open_video(new TabRef.Explicit(tabId), index);
    }

    private String service_douyin_open_video(TabRef tabRef, int index) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.DOUYIN_OPEN_VIDEO,
                new DouyinOpenVideoPayload(index),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_douyin_ui_main(String op, String label) {
        return service_douyin_ui(new TabRef.Main(), op, label);
    }

    public String service_douyin_ui_tab(long tabId, String op, String label) {
        return service_douyin_ui(new TabRef.Explicit(tabId), op, label);
    }

    private String service_douyin_ui(TabRef tabRef, String op, String label) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.DOUYIN_UI,
                new DouyinUiPayload(op, label),
                DEFAULT_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_type_dm_draft_active(String text) {
        return service_type_dm_draft_active(text, false);
    }

    public String service_type_dm_draft_active(String text, boolean send) {
        String active = service_type_dm_draft(new TabRef.Active(), text, send, false);
        if (!active.contains("\"NO_TARGET_TAB\"") && !active.contains("tab_ref=\\\"active\\\"")) {
            return active;
        }
        return service_type_dm_draft(new TabRef.Main(), text, send, false);
    }

    public String service_type_dm_draft_tab(long tabId, String text, boolean send) {
        return service_type_dm_draft(new TabRef.Explicit(tabId), text, send, false);
    }

    public String service_send_dm_active(String text) {
        String active = service_type_dm_draft(new TabRef.Active(), text, true, true);
        if (!active.contains("\"NO_TARGET_TAB\"") && !active.contains("tab_ref=\\\"active\\\"")) {
            return active;
        }
        return service_type_dm_draft(new TabRef.Main(), text, true, true);
    }

    public String service_send_dm_tab(long tabId, String text) {
        return service_type_dm_draft(new TabRef.Explicit(tabId), text, true, true);
    }

    private String service_type_dm_draft(TabRef tabRef, String text, boolean send, boolean sendOnly) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                tabRef,
                ActionKind.TYPE_DM_DRAFT,
                new TypeDmDraftPayload(text, send, sendOnly),
                DM_DEADLINE_MS);
        return executePlan(session, List.of(req));
    }

    public String service_douyin_comment_network_main(
            String op,
            Integer maxPages,
            Integer maxBodyBytes,
            Integer ttlMs) {
        return service_douyin_comment_network_main(op, maxPages, maxBodyBytes, ttlMs, DEFAULT_DEADLINE_MS);
    }

    /**
     * 同上,可指定 deadlineMs。drain 这类"读扩展侧已捕获缓冲"的幂等只读 op 正常 sub-second 返回,
     * 给它更短 deadline(见 ExtensionDouyinBrowserAdapter.COMMENT_DRAIN_DEADLINE_MS)可把"取消时恰
     * 卡在 drain RPC"的响应窗口从 15s+ 压到秒级,而不走 interrupt(NIO 中断会关 H2 FileChannel)。
     * 即便偶发 deadline 截断,扩展侧捕获缓冲带 TTL 仍在,下次 drain 可重读,不丢评论。
     */
    public String service_douyin_comment_network_main(
            String op,
            Integer maxPages,
            Integer maxBodyBytes,
            Integer ttlMs,
            long deadlineMs) {
        BrowserSession session = resolveSession();
        if (session == null) return noSession();

        ActionRequest req = new ActionRequest(
                newMsgId(),
                new TabRef.Main(),
                ActionKind.DOUYIN_COMMENT_NETWORK,
                new DouyinCommentNetworkPayload(op, maxPages, maxBodyBytes, ttlMs),
                deadlineMs);
        return executePlan(session, List.of(req));
    }

    String extension_browser_observe_tab(TabRef tabRef, String filter, @Nullable ToolContext ctx) {
        BrowserSession session = resolveSession(ctx);
        if (session == null) return noSession();

        String resolvedFilter = defaulted(filter, "default");
        try {
            // requestFresh, NOT request: observe is the agent's "read the page
            // NOW" call. A cached snapshot here can replay a pre-navigation page
            // (e.g. the homepage after a search submit that the SPA route change
            // never invalidated), making the agent think its action failed and
            // loop. Fetch live; the result still repopulates the cache FRESH so
            // the click the agent grounds next reuses it.
            var snapshot = snapshotService
                    .requestFresh(session, tabRef, resolvedFilter)
                    .block(java.time.Duration.ofSeconds(15));
            if (snapshot == null) {
                return error("SNAPSHOT_FAILED", "PageSnapshotService returned null");
            }
            return json(Map.of(
                    "ok", true,
                    "snapshot_id", snapshot.snapshotId(),
                    "url", snapshot.url(),
                    "title", snapshot.title(),
                    "viewport", Map.of("w", snapshot.viewport().w(), "h", snapshot.viewport().h()),
                    "tree", snapshot.tree()));
        } catch (Exception e) {
            if (e instanceof SnapshotFailureException sfe) {
                return error(sfe.code(), sfe.getMessage());
            }
            if (e.getCause() instanceof SnapshotFailureException sfe) {
                return error(sfe.code(), sfe.getMessage());
            }
            return error("SNAPSHOT_FAILED", e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    /** Background/service callers (lead acquisition flows) that have no
     *  per-conversation ToolContext. Resolves by the configured subject + the
     *  single-session fallback only — no per-user routing. */
    private BrowserSession resolveSession() {
        return resolveSession(null);
    }

    /**
     * Resolve the active extension session for the CURRENT authenticated user.
     *
     * <p>Resolution order (every subject-keyed hit is liveness-checked via
     * {@link BrowserSessionRegistry#findLiveBySubject} — an open socket with a
     * fresh heartbeat — so a session inside the ~40 s reaper window is treated as
     * a miss and evicted on the spot rather than dispatched into):
     * <ol>
     *   <li>Derive the caller's subject from {@code ctx} — the same value the
     *       edge handshake registered under ({@code EdgePrincipal.subject()}:
     *       JWT=username / PAT=userId.toString). For web-originated chats the
     *       {@link ChatOrigin#requesterId()} carries that username, so we use it
     *       as the {@code findLiveBySubject} key to pick THIS user's browser even
     *       when several browsers are connected. The registry's alias map (契约4)
     *       means a username key also hits a session that registered under the
     *       owner's PAT userId, and vice versa.</li>
     *   <li>Fall back to {@link RoutingSubjectContext#get()} — the edge subject a
     *       background / service task (e.g. 抖音获客, group D) bound on its worker
     *       thread when there is no per-conversation {@code ctx} (契约3).</li>
     *   <li>Fall back to the configured {@code defaultSubject}.</li>
     *   <li>Only when no subject resolves a LIVE session AND exactly one browser
     *       is live do we target it (single-session-per-tenant fallback). With
     *       more than one live session this stays strict (returns null) rather
     *       than risk driving the wrong user's browser — callers surface
     *       {@code AMBIGUOUS_SESSION} for that case (see {@link #noSession()}).</li>
     * </ol>
     */
    private BrowserSession resolveSession(@Nullable ToolContext ctx) {
        // (1) Precise per-user routing via the authenticated subject from ctx.
        String subject = subjectFromContext(ctx);
        if (subject != null) {
            Optional<BrowserSession> mine = registry.findLiveBySubject(subject);
            if (mine.isPresent()) {
                return mine.get();
            }
        }

        // (2) Background / service tasks with no ctx: the thread-bound routing
        //     subject set by the task entry point (契约3). Precise per-user
        //     routing for the lead-acquisition flows.
        String bound = RoutingSubjectContext.get();
        if (bound != null && !bound.equals(subject)) {
            Optional<BrowserSession> mine = registry.findLiveBySubject(bound);
            if (mine.isPresent()) {
                return mine.get();
            }
        }

        // (3) Configured default subject (Phase 3 single-tenant deployments).
        Optional<BrowserSession> s = registry.findLiveBySubject(defaultSubject);
        if (s.isPresent()) {
            return s.get();
        }

        // (4) Single-session-per-tenant fallback — only safe when exactly one
        // browser is connected. The edge session is registered under the
        // authenticated user's subject, NOT the configured `defaultSubject`, so
        // when the subject above couldn't be derived (e.g. background/service
        // callers with no ToolContext) we still target the lone browser — but
        // only if it is actually live (open socket + fresh heartbeat).
        var all = registry.snapshot();
        if (all.size() == 1) {
            BrowserSession only = registry.find(all.getFirst().sessionId()).orElse(null);
            return registry.isLive(only) ? only : null;
        }
        return null;
    }

    /**
     * Extract the authenticated caller's subject from the ToolContext, matching
     * what the edge handshake registered ({@code EdgePrincipal.subject()}). For
     * the web entry point {@link ChatOrigin#requesterId()} holds the username
     * (= the JWT subject); cron/system origins carry a non-user sentinel
     * ("system") which we ignore so they fall through to the configured subject
     * + single-session fallback. Returns null when no usable subject is present.
     */
    @Nullable
    private String subjectFromContext(@Nullable ToolContext ctx) {
        if (ctx == null) return null;
        ChatOrigin origin = ChatOrigin.from(ctx);
        if (origin.cronOrigin()) return null;
        String requesterId = origin.requesterId();
        if (requesterId == null || requesterId.isBlank()) return null;
        if ("system".equals(requesterId) || "anonymous".equals(requesterId)) return null;
        return requesterId;
    }

    /** Execute the given list of action requests sequentially via PlanExecutionService.
     *  Blocks on the resulting Mono for tool semantics (LLM expects a return value). */
    private String executePlan(BrowserSession session, List<ActionRequest> plan) {
        try {
            PlanResult result = planExec.execute(session, plan).block(java.time.Duration.ofSeconds(60));
            if (result == null) {
                return error("EXECUTION_FAILED", "PlanExecutionService returned null");
            }
            return switch (result) {
                case PlanResult.Success ok -> json(Map.of(
                        "ok", true,
                        "elapsed_ms", ok.completed().stream()
                                .mapToLong(s -> s.elapsedMs()).sum(),
                        "steps", ok.completed().size(),
                        "results", actionPayloads(ok.completed())));
                case PlanResult.Partial p -> json(Map.of(
                        "ok", false,
                        "code", p.failed().code(),
                        "message", p.failed().message(),
                        "completed_before_failure", p.completed().size(),
                        "results", actionPayloads(p.completed())));
            };
        } catch (Exception e) {
            return error("EXECUTION_FAILED", e.getMessage());
        }
    }

    private List<Map<String, Object>> actionPayloads(List<Success> completed) {
        return completed.stream()
                .map(success -> Map.of(
                        "kind", (Object) successKind(success),
                        "elapsed_ms", (Object) success.elapsedMs(),
                        "payload", mapper.convertValue(success.payload(), Map.class)))
                .toList();
    }

    private String successKind(Success success) {
        return switch (success.payload()) {
            case NavigateSuccess ignored -> "navigate";
            case ClickSuccess ignored -> "click";
            case TypeSuccess ignored -> "type";
            case vip.mate.browser.edge.action.PressKeySuccess ignored -> "press_key";
            case ScrollSuccess ignored -> "scroll";
            case ScrollRegionSuccess ignored -> "scroll_region";
            case RegisterRegionSuccess ignored -> "register_region";
            case DetectRegionSuccess ignored -> "detect_region";
            case ExtractRegionSuccess ignored -> "extract_region";
            case OpenAuthorFromCommentSuccess ignored -> "open_author_from_comment";
            case ClickProfileActionSuccess ignored -> "click_profile_action";
            case TypeDmDraftSuccess ignored -> "type_dm_draft";
            case CloseTabSuccess ignored -> "close_tab";
            case DouyinCommentNetworkSuccess ignored -> "douyin_comment_network";
            case DouyinSearchSuccess ignored -> "douyin_search";
            case DouyinOpenVideoSuccess ignored -> "douyin_open_video";
            case DouyinUiSuccess ignored -> "douyin_ui";
            case MoveMouseSuccess ignored -> "move_mouse";
            case WaitSuccess ignored -> "wait";
        };
    }

    private String noSession() {
        int live = registry.size();
        // resolveSession() returned null. Two distinct shapes:
        //  - >1 browser connected but none matched the caller's subject → we
        //    refuse to guess which is the user's; surface AMBIGUOUS_SESSION so
        //    the agent tells the user to connect from the right account/device.
        //  - 0 (or 1 that still didn't resolve) → the target device isn't
        //    connected; NO_SESSION with onboarding guidance.
        if (live > 1) {
            return error("AMBIGUOUS_SESSION",
                    "目标设备未连接：当前有 " + live + " 个浏览器在线，但没有一个匹配当前登录用户。"
                            + " 请用发起任务的同一账号在浏览器扩展里连接 MateClaw（或在该浏览器粘贴本人 PAT），"
                            + " 再重试。");
        }
        return error("NO_SESSION",
                "目标设备未连接：没有可用的浏览器。请打开 MateClaw 管理后台 → Settings → Browser"
                        + " 点击“Connect Browser”，或在浏览器扩展侧栏粘贴 PAT 后重试。");
    }

    private String error(String code, String message) {
        return json(Map.of("ok", false, "code", code, "message", message == null ? "" : message));
    }

    private String json(Map<String, Object> obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // Should be impossible for Map<String, Object> with primitive values
            log.warn("[ExtensionBrowserTool] serialise failed: {}", e.getMessage());
            return "{\"ok\":false,\"code\":\"SERIALISE_FAILED\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String newMsgId() {
        return UUID.randomUUID().toString();
    }

    private static String defaulted(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Empty / blank strings from LLM tool-call args mean "not set"; normalise to null. */
    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
