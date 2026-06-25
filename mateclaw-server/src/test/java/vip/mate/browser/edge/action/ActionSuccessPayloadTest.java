package vip.mate.browser.edge.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionSuccessPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void navigateSuccess_roundTrip() throws Exception {
        var success = new NavigateSuccess("https://example.com/final", 204, "network_idle");

        String json = mapper.writeValueAsString(success);
        NavigateSuccess back = mapper.readValue(json, NavigateSuccess.class);

        assertThat(json)
                .contains("\"final_url\":\"https://example.com/final\"")
                .contains("\"http_status\":204")
                .contains("\"load_state\":\"network_idle\"");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void clickSuccess_roundTrip() throws Exception {
        var success = new ClickSuccess();

        String json = mapper.writeValueAsString(success);
        ClickSuccess back = mapper.readValue(json, ClickSuccess.class);

        assertThat(json).isEqualTo("{}");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void typeSuccess_roundTrip() throws Exception {
        var success = new TypeSuccess(5);

        String json = mapper.writeValueAsString(success);
        TypeSuccess back = mapper.readValue(json, TypeSuccess.class);

        assertThat(json).contains("\"chars_typed\":5");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void pressKeySuccess_allowsEmptyPayloadForOlderExtensions() throws Exception {
        ActionSuccessPayload back = mapper.readValue(
                "{\"kind\":\"press_key\"}",
                ActionSuccessPayload.class);

        assertThat(back).isEqualTo(new PressKeySuccess(""));
    }

    @Test
    void scrollSuccess_roundTrip() throws Exception {
        var success = new ScrollSuccess();

        String json = mapper.writeValueAsString(success);
        ScrollSuccess back = mapper.readValue(json, ScrollSuccess.class);

        assertThat(json).isEqualTo("{}");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void scrollRegionSuccess_preservesMovedEvidence() throws Exception {
        String json = """
                {"kind":"scroll_region","regionKey":"douyin.comments","mode":"comment_region_wheel","moved":true,"reason":"comment_window_advanced","forwardProgress":true,"visibleItemCount":6,"newVisibleItemCount":2}
                """;

        ActionSuccessPayload back = mapper.readValue(json, ActionSuccessPayload.class);

        assertThat(back).isInstanceOf(ScrollRegionSuccess.class);
        ScrollRegionSuccess success = (ScrollRegionSuccess) back;
        assertThat(success.regionKey()).isEqualTo("douyin.comments");
        assertThat(success.mode()).isEqualTo("comment_region_wheel");
        assertThat(success.moved()).isTrue();
        assertThat(success.reason()).isEqualTo("comment_window_advanced");
        assertThat(success.forwardProgress()).isTrue();
        assertThat(success.visibleItemCount()).isEqualTo(6);
        assertThat(success.newVisibleItemCount()).isEqualTo(2);
    }

    @Test
    void moveMouseSuccess_roundTrip() throws Exception {
        var success = new MoveMouseSuccess(123, 9);

        String json = mapper.writeValueAsString(success);
        MoveMouseSuccess back = mapper.readValue(json, MoveMouseSuccess.class);

        assertThat(json)
                .contains("\"arrived_at_ms\":123")
                .contains("\"waypoints\":9");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void waitSuccess_roundTrip() throws Exception {
        var success = new WaitSuccess(750);

        String json = mapper.writeValueAsString(success);
        WaitSuccess back = mapper.readValue(json, WaitSuccess.class);

        assertThat(json).contains("\"waited_ms\":750");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void extractRegionSuccess_roundTrip() throws Exception {
        var item = new ExtractRegionSuccess.Item(
                "hello",
                "link",
                "a",
                "https://example.com",
                new ExtractRegionSuccess.BBox(1, 2, 3, 4),
                "douyin_comment",
                "alice",
                java.util.List.of("https://example.com"),
                true);
        var success = new ExtractRegionSuccess(
                "douyin.comments",
                java.util.List.of(item),
                java.util.Map.of(
                        "commentListCount", 1,
                        "documentCommentItems", 66,
                        "firstTexts", java.util.List.of("hello")));

        String json = mapper.writeValueAsString(success);
        ExtractRegionSuccess back = mapper.readValue(json, ExtractRegionSuccess.class);

        assertThat(json)
                .contains("\"regionKey\":\"douyin.comments\"")
                .contains("\"itemType\":\"douyin_comment\"")
                .contains("\"visibleInRegion\":true")
                .contains("\"commentListCount\":1")
                .contains("\"documentCommentItems\":66")
                .contains("\"bbox\":{\"x\":1.0,\"y\":2.0,\"width\":3.0,\"height\":4.0}");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void detectRegionSuccess_roundTrip() throws Exception {
        var success = new DetectRegionSuccess(
                "douyin.comments",
                new RegisterRegionPayload.Rect(10, 20, 300, 400),
                new DetectRegionSuccess.SafePoint(220, 260),
                "dom_detect:comment-panel",
                null, null, null);

        String json = mapper.writeValueAsString(success);
        DetectRegionSuccess back = mapper.readValue(json, DetectRegionSuccess.class);

        assertThat(json)
                .contains("\"regionKey\":\"douyin.comments\"")
                .contains("\"rect\":{\"x\":10.0,\"y\":20.0,\"width\":300.0,\"height\":400.0}")
                .contains("\"safePoint\":{\"x\":220.0,\"y\":260.0}");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void abstractInterfaceDispatch_byKindDiscriminator() throws Exception {
        // NAME-based dispatch: the "kind" property is the source of truth.
        // The empty ClickSuccess and ScrollSuccess records (no fields) are
        // only distinguishable via this discriminator — that's why this
        // package abandoned DEDUCTION.
        String moveJson = "{\"kind\":\"move_mouse\",\"arrived_at_ms\":123,\"waypoints\":9}";
        ActionSuccessPayload moveBack = mapper.readValue(moveJson, ActionSuccessPayload.class);
        assertThat(moveBack).isEqualTo(new MoveMouseSuccess(123, 9));

        String clickJson = "{\"kind\":\"click\"}";
        ActionSuccessPayload clickBack = mapper.readValue(clickJson, ActionSuccessPayload.class);
        assertThat(clickBack).isInstanceOf(ClickSuccess.class);

        String scrollJson = "{\"kind\":\"scroll\"}";
        ActionSuccessPayload scrollBack = mapper.readValue(scrollJson, ActionSuccessPayload.class);
        assertThat(scrollBack).isInstanceOf(ScrollSuccess.class);

        String extractJson = """
            {"kind":"extract_region","regionKey":"douyin.comments","items":[],"diagnostics":{"commentListCount":1}}
            """;
        ActionSuccessPayload extractBack = mapper.readValue(extractJson, ActionSuccessPayload.class);
        assertThat(extractBack).isEqualTo(new ExtractRegionSuccess(
                "douyin.comments",
                java.util.List.of(),
                java.util.Map.of("commentListCount", 1)));

        String detectJson = """
            {"kind":"detect_region","regionKey":"douyin.comments","rect":{"x":10,"y":20,"width":300,"height":400},"safePoint":{"x":220,"y":260},"source":"dom"}
            """;
        ActionSuccessPayload detectBack = mapper.readValue(detectJson, ActionSuccessPayload.class);
        assertThat(detectBack).isEqualTo(new DetectRegionSuccess(
                "douyin.comments",
                new RegisterRegionPayload.Rect(10, 20, 300, 400),
                new DetectRegionSuccess.SafePoint(220, 260),
                "dom",
                null, null, null));
    }

    @Test
    void douyinCommentNetworkSuccess_roundTrip() throws Exception {
        var page = new DouyinCommentNetworkSuccess.Page(
                "https://www.douyin.com/aweme/v1/web/comment/list/",
                "request-1",
                200,
                "{\"comments\":[]}",
                false,
                123L);
        var success = new DouyinCommentNetworkSuccess("drain", java.util.List.of(page), 1, 0);

        String json = mapper.writeValueAsString(success);
        DouyinCommentNetworkSuccess back = mapper.readValue(json, DouyinCommentNetworkSuccess.class);

        assertThat(json)
                .contains("\"op\":\"drain\"")
                .contains("\"requestId\":\"request-1\"")
                .contains("\"base64Encoded\":false");
        assertThat(back).isEqualTo(success);
    }

    @Test
    void abstractInterfaceDispatch_douyinCommentNetworkIgnoresExtensionExtras() throws Exception {
        String json = """
            {"kind":"douyin_comment_network","op":"drain","pages":[],"drainedCount":0,"capturing":true,"expiresAtMs":123}
            """;

        ActionSuccessPayload back = mapper.readValue(json, ActionSuccessPayload.class);

        assertThat(back).isEqualTo(new DouyinCommentNetworkSuccess("drain", java.util.List.of(), 0, 0));
    }

    @Test
    void abstractInterfaceDispatch_closeTab() throws Exception {
        ActionSuccessPayload back = mapper.readValue(
                "{\"kind\":\"close_tab\",\"tabId\":42}",
                ActionSuccessPayload.class);

        assertThat(back).isEqualTo(new CloseTabSuccess(42));
    }
}
