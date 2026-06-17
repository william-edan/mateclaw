package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;

/**
 * Sealed polymorphic root for action.result success payloads.
 *
 * <p>Uses {@code NAME} dispatch on a {@code "kind"} property because
 * {@link ClickSuccess} and {@link ScrollSuccess} are both empty records
 * and cannot be distinguished by DEDUCTION. Concrete subtypes carry
 * {@code @JsonTypeInfo(use=NONE)} so direct {@code readValue(json, Concrete.class)}
 * still works without the discriminator.
 *
 * <p>Wire names mirror {@link ActionKind} lowercase: {@code navigate,
 * click, type, press_key, scroll, move_mouse, wait}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NavigateSuccess.class,  name = "navigate"),
        @JsonSubTypes.Type(value = ClickSuccess.class,     name = "click"),
        @JsonSubTypes.Type(value = TypeSuccess.class,      name = "type"),
        @JsonSubTypes.Type(value = PressKeySuccess.class,  name = "press_key"),
        @JsonSubTypes.Type(value = ScrollSuccess.class,    name = "scroll"),
        @JsonSubTypes.Type(value = ScrollRegionSuccess.class, name = "scroll_region"),
        @JsonSubTypes.Type(value = RegisterRegionSuccess.class, name = "register_region"),
        @JsonSubTypes.Type(value = DetectRegionSuccess.class, name = "detect_region"),
        @JsonSubTypes.Type(value = ExtractRegionSuccess.class, name = "extract_region"),
        @JsonSubTypes.Type(value = OpenAuthorFromCommentSuccess.class, name = "open_author_from_comment"),
        @JsonSubTypes.Type(value = ClickProfileActionSuccess.class, name = "click_profile_action"),
        @JsonSubTypes.Type(value = TypeDmDraftSuccess.class, name = "type_dm_draft"),
        @JsonSubTypes.Type(value = CloseTabSuccess.class, name = "close_tab"),
        @JsonSubTypes.Type(value = DouyinCommentNetworkSuccess.class, name = "douyin_comment_network"),
        @JsonSubTypes.Type(value = DouyinSearchSuccess.class, name = "douyin_search"),
        @JsonSubTypes.Type(value = DouyinOpenVideoSuccess.class, name = "douyin_open_video"),
        @JsonSubTypes.Type(value = DouyinUiSuccess.class, name = "douyin_ui"),
        @JsonSubTypes.Type(value = MoveMouseSuccess.class, name = "move_mouse"),
        @JsonSubTypes.Type(value = WaitSuccess.class,      name = "wait")
})
public sealed interface ActionSuccessPayload
        permits NavigateSuccess, ClickSuccess, TypeSuccess,
                PressKeySuccess, ScrollSuccess, ScrollRegionSuccess,
                RegisterRegionSuccess, DetectRegionSuccess, ExtractRegionSuccess,
                OpenAuthorFromCommentSuccess, ClickProfileActionSuccess, TypeDmDraftSuccess,
                CloseTabSuccess, DouyinCommentNetworkSuccess, DouyinSearchSuccess, DouyinOpenVideoSuccess, DouyinUiSuccess, MoveMouseSuccess, WaitSuccess {
}
