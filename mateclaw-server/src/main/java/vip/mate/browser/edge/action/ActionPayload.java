package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;

/**
 * Sealed polymorphic root for atomic browser-action parameter payloads.
 *
 * <p>Jackson polymorphism uses {@code NAME} dispatch on a {@code "kind"}
 * property — DEDUCTION is unusable here because {@link ClickPayload} and
 * {@link MoveMousePayload} share the {@code (x, y)} prefix, and the empty
 * success records are even more indistinguishable. Concrete subtypes
 * carry {@code @JsonTypeInfo(use=NONE)} so direct {@code readValue(json, Concrete.class)}
 * still works without requiring the discriminator.
 *
 * <p>Wire names mirror {@link ActionKind} lowercase: {@code navigate,
 * click, type, press_key, scroll, move_mouse, wait}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NavigatePayload.class,  name = "navigate"),
        @JsonSubTypes.Type(value = ClickPayload.class,     name = "click"),
        @JsonSubTypes.Type(value = TypePayload.class,      name = "type"),
        @JsonSubTypes.Type(value = PressKeyPayload.class,  name = "press_key"),
        @JsonSubTypes.Type(value = ScrollPayload.class,    name = "scroll"),
        @JsonSubTypes.Type(value = ScrollRegionPayload.class, name = "scroll_region"),
        @JsonSubTypes.Type(value = RegisterRegionPayload.class, name = "register_region"),
        @JsonSubTypes.Type(value = DetectRegionPayload.class, name = "detect_region"),
        @JsonSubTypes.Type(value = ExtractRegionPayload.class, name = "extract_region"),
        @JsonSubTypes.Type(value = OpenAuthorFromCommentPayload.class, name = "open_author_from_comment"),
        @JsonSubTypes.Type(value = ClickProfileActionPayload.class, name = "click_profile_action"),
        @JsonSubTypes.Type(value = TypeDmDraftPayload.class, name = "type_dm_draft"),
        @JsonSubTypes.Type(value = CloseTabPayload.class, name = "close_tab"),
        @JsonSubTypes.Type(value = DouyinCommentNetworkPayload.class, name = "douyin_comment_network"),
        @JsonSubTypes.Type(value = DouyinSearchPayload.class, name = "douyin_search"),
        @JsonSubTypes.Type(value = DouyinOpenVideoPayload.class, name = "douyin_open_video"),
        @JsonSubTypes.Type(value = DouyinUiPayload.class, name = "douyin_ui"),
        @JsonSubTypes.Type(value = MoveMousePayload.class, name = "move_mouse"),
        @JsonSubTypes.Type(value = WaitPayload.class,      name = "wait")
})
public sealed interface ActionPayload
        permits NavigatePayload, ClickPayload, TypePayload,
                PressKeyPayload, ScrollPayload, ScrollRegionPayload,
                RegisterRegionPayload, DetectRegionPayload, ExtractRegionPayload,
                OpenAuthorFromCommentPayload, ClickProfileActionPayload, TypeDmDraftPayload,
                CloseTabPayload, DouyinCommentNetworkPayload, DouyinSearchPayload, DouyinOpenVideoPayload, DouyinUiPayload, MoveMousePayload, WaitPayload {
}
