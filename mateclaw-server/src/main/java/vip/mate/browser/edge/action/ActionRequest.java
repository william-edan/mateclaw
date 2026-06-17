package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire-format envelope for an {@code action.execute} command.
 *
 * <p>This is the strongly-typed Java mirror of the payload carried inside
 * an {@code EdgeMessage} whose {@code kind = "action.execute"}. The shape
 * is documented in {@code docs/specs/edge-protocol.md} §"action.execute":
 *
 * <pre>
 * {
 *   "msg_id":     "uuid",
 *   "tab_ref":    "main" | "active" | &lt;int&gt;,
 *   "kind":       "navigate" | "click" | "type" | "scroll" | "move_mouse" | "wait",
 *   "params":     { ... per-action body, see {@link ActionPayload} subtypes },
 *   "deadline_ms": 30000
 * }
 * </pre>
 *
 * <p><b>Why the outer {@code kind} is redundant with the inner
 * {@code params.kind}</b>: {@link ActionPayload} uses Jackson NAME
 * dispatch on its own {@code "kind"} property because {@link ClickPayload}
 * and {@link MoveMousePayload} share the {@code (x, y)} prefix and the
 * empty-body subtypes are otherwise indistinguishable (audit fix —
 * formerly {@code DEDUCTION}). The outer {@code kind} field is kept so
 * routing components (Native Host, SW) can dispatch without parsing
 * {@code params}. The compact constructor enforces that the two agree —
 * a mismatch is a programmer error caught at the boundary.
 *
 * <p>Field invariants enforced by the compact constructor:
 * <ul>
 *     <li>{@code msgId} non-null, non-empty</li>
 *     <li>{@code tabRef} non-null</li>
 *     <li>{@code kind} non-null</li>
 *     <li>{@code params} non-null and its concrete type matches {@code kind}</li>
 *     <li>{@code deadlineMs} strictly positive</li>
 * </ul>
 */
public record ActionRequest(
        @JsonProperty("msg_id")      String msgId,
        @JsonProperty("tab_ref")     TabRef tabRef,
        @JsonProperty("kind")        ActionKind kind,
        @JsonProperty("params")      ActionPayload params,
        @JsonProperty("deadline_ms") long deadlineMs
) {

    @JsonCreator
    public ActionRequest {
        if (msgId == null || msgId.isEmpty()) {
            throw new IllegalArgumentException("msg_id is required");
        }
        if (tabRef == null) {
            throw new IllegalArgumentException("tab_ref is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (params == null) {
            throw new IllegalArgumentException("params is required");
        }
        if (deadlineMs <= 0) {
            throw new IllegalArgumentException("deadline_ms must be > 0");
        }
        if (!kindMatchesPayload(kind, params)) {
            throw new IllegalArgumentException(
                    "kind=" + kind + " does not match params type "
                            + params.getClass().getSimpleName());
        }
    }

    /**
     * Boundary check — the outer routing {@link ActionKind} must agree
     * with the {@link ActionPayload} concrete subtype carried in
     * {@code params}. Catches caller mistakes (e.g. forgetting to update
     * both halves when changing an action) before the request reaches
     * the action executor.
     */
    private static boolean kindMatchesPayload(ActionKind k, ActionPayload p) {
        return switch (k) {
            case NAVIGATE   -> p instanceof NavigatePayload;
            case CLICK      -> p instanceof ClickPayload;
            case TYPE       -> p instanceof TypePayload;
            case PRESS_KEY  -> p instanceof PressKeyPayload;
            case SCROLL     -> p instanceof ScrollPayload;
            case SCROLL_REGION -> p instanceof ScrollRegionPayload;
            case REGISTER_REGION -> p instanceof RegisterRegionPayload;
            case DETECT_REGION -> p instanceof DetectRegionPayload;
            case EXTRACT_REGION -> p instanceof ExtractRegionPayload;
            case OPEN_AUTHOR_FROM_COMMENT -> p instanceof OpenAuthorFromCommentPayload;
            case CLICK_PROFILE_ACTION -> p instanceof ClickProfileActionPayload;
            case TYPE_DM_DRAFT -> p instanceof TypeDmDraftPayload;
            case CLOSE_TAB -> p instanceof CloseTabPayload;
            case DOUYIN_COMMENT_NETWORK -> p instanceof DouyinCommentNetworkPayload;
            case DOUYIN_SEARCH -> p instanceof DouyinSearchPayload;
            case DOUYIN_OPEN_VIDEO -> p instanceof DouyinOpenVideoPayload;
            case DOUYIN_UI -> p instanceof DouyinUiPayload;
            case MOVE_MOUSE -> p instanceof MoveMousePayload;
            case WAIT       -> p instanceof WaitPayload;
        };
    }
}
