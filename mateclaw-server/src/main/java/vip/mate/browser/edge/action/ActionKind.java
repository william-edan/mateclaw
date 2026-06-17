package vip.mate.browser.edge.action;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ActionKind {
    NAVIGATE("navigate"),
    CLICK("click"),
    TYPE("type"),
    PRESS_KEY("press_key"),
    SCROLL("scroll"),
    SCROLL_REGION("scroll_region"),
    REGISTER_REGION("register_region"),
    DETECT_REGION("detect_region"),
    EXTRACT_REGION("extract_region"),
    OPEN_AUTHOR_FROM_COMMENT("open_author_from_comment"),
    CLICK_PROFILE_ACTION("click_profile_action"),
    TYPE_DM_DRAFT("type_dm_draft"),
    CLOSE_TAB("close_tab"),
    DOUYIN_COMMENT_NETWORK("douyin_comment_network"),
    DOUYIN_SEARCH("douyin_search"),
    DOUYIN_OPEN_VIDEO("douyin_open_video"),
    DOUYIN_UI("douyin_ui"),
    MOVE_MOUSE("move_mouse"),
    WAIT("wait");

    private final String wire;

    ActionKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ActionKind fromWire(String s) {
        for (ActionKind k : values()) {
            if (k.wire.equals(s)) {
                return k;
            }
        }
        throw new IllegalArgumentException("unknown ActionKind: " + s);
    }
}
