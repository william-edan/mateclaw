package vip.mate.lead.douyin.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.lead.douyin.collect.DouyinCommentCollector;
import vip.mate.lead.douyin.model.DouyinCommentItem;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.EngagementResult;
import vip.mate.tool.builtin.ExtensionBrowserTool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtensionDouyinBrowserAdapterSafetyTest {

    @Test
    void engagementResultFailedKeepsAllSuccessFlagsFalse() {
        DouyinCommentItem comment = new DouyinCommentItem(
                "video",
                "comment",
                null,
                "Ly",
                "https://www.douyin.com/user/ly",
                null,
                "对于99%的人用豆包就行了。",
                null,
                null,
                null,
                null);

        EngagementResult failed = EngagementResult.failed(comment, "NOT_DOUYIN_PROFILE", "not douyin");

        assertThat(failed.profileOpened()).isFalse();
        assertThat(failed.followConfirmed()).isFalse();
        assertThat(failed.dmOpened()).isFalse();
        assertThat(failed.draftTyped()).isFalse();
        assertThat(failed.sent()).isFalse();
    }

    @Test
    void searchBoxLocatorPrefersMainSearchInputOverAiSearch() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Button[ref=ref_1, frame=0]: AI搜索 @{650,300 120x36}
                Searchbox[ref=ref_2, frame=0]: 搜索你感兴趣的内容 @{420,18 360x40}
                Button[ref=ref_3, frame=0]: 搜索 @{790,18 64x40}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point = adapter.findDouyinSearchBoxPoint(tree);

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(600d);
        assertThat(point.y()).isEqualTo(38d);
    }

    @Test
    void followAndDraftFallsBackToDomProfileActionWhenA11yOmitsDmButton() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinCommentItem comment = new DouyinCommentItem(
                "video",
                "comment",
                null,
                "",
                "https://www.douyin.com/user/MS4w",
                null,
                "不建议大家用易企秀，慢出心脏病了。",
                null,
                null,
                null,
                null);
        String profileWithoutDmInA11y = """
                {"ok":true,"url":"https://www.douyin.com/user/MS4w","title":"霞姐一百岁 - 抖音","viewport":{"w":1280,"h":800},"tree":"Heading[ref=ref_1, frame=0]: 霞姐一百岁 @{520,90 180x36}\\nStaticText[ref=ref_2, frame=0]: 已关注 @{912,224 86x36}\\nStaticText[ref=ref_3, frame=0]: 粉丝 32 @{520,150 90x24}\\nTab[ref=ref_4, frame=0]: 作品 @{520,280 80x32}"}
                """;
        String dmPage = """
                {"ok":true,"url":"https://www.douyin.com/im/霞姐一百岁","title":"私信 - 抖音","viewport":{"w":1280,"h":800},"tree":"StaticText[ref=ref_1, frame=0]: 私信 @{420,80 80x28}\\nStaticText[ref=ref_2, frame=0]: 发送消息 @{520,140 120x28}\\nTextbox[ref=ref_3, frame=0]: 你好 @{520,700 360x44}"}
                """;

        when(browser.extension_browser_navigate(any(), any(), any())).thenReturn("{\"ok\":true}");
        when(browser.service_observe_active("all")).thenReturn(profileWithoutDmInA11y, dmPage, dmPage);
        when(browser.service_click_profile_action_active(List.of("私信", "发私信", "Message", "发消息")))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"label\":\"私信\"}}]}");
        when(browser.service_type_dm_draft_active("你好", false)).thenReturn("{\"ok\":true}");

        EngagementResult result = adapter.followAndDraft(comment, "你好", false);

        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.dmOpened()).isTrue();
        assertThat(result.draftTyped()).isTrue();
        assertThat(result.failureCode()).isNull();
        verify(browser, never()).service_click_active(anyDouble(), anyDouble());
        verify(browser).service_click_profile_action_active(List.of("私信", "发私信", "Message", "发消息"));
    }

    @Test
    void followAndDraftRetriesDomProfileActionWhenA11yClickDoesNotOpenDmPage() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinCommentItem comment = new DouyinCommentItem(
                "video",
                "comment",
                null,
                "霞姐一百岁",
                "https://www.douyin.com/user/MS4w",
                null,
                "不建议大家用易企秀，慢出心脏病了。",
                null,
                null,
                null,
                null);
        String profileWithDmInA11y = """
                {"ok":true,"url":"https://www.douyin.com/user/MS4w","title":"霞姐一百岁 - 抖音","viewport":{"w":1280,"h":800},"tree":"Heading[ref=ref_1, frame=0]: 霞姐一百岁 @{520,90 180x36}\\nStaticText[ref=ref_2, frame=0]: 已关注 @{912,224 86x36}\\nButton[ref=ref_3, frame=0]: 私信 @{1010,224 86x36}"}
                """;
        String dmPage = """
                {"ok":true,"url":"https://www.douyin.com/im/霞姐一百岁","title":"私信 - 抖音","viewport":{"w":1280,"h":800},"tree":"StaticText[ref=ref_1, frame=0]: 私信 @{420,80 80x28}\\nStaticText[ref=ref_2, frame=0]: 发送消息 @{520,140 120x28}\\nTextbox[ref=ref_3, frame=0]: 你好 @{520,700 360x44}"}
                """;

        when(browser.extension_browser_navigate(any(), any(), any())).thenReturn("{\"ok\":true}");
        when(browser.service_observe_active("all")).thenReturn(
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                profileWithDmInA11y,
                dmPage,
                dmPage);
        when(browser.service_click_active(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_click_profile_action_active(List.of("私信", "发私信", "Message", "发消息")))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"label\":\"私信\"}}]}");
        when(browser.service_type_dm_draft_active("你好", false)).thenReturn("{\"ok\":true}");

        EngagementResult result = adapter.followAndDraft(comment, "你好", false);

        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.dmOpened()).isTrue();
        assertThat(result.draftTyped()).isTrue();
        assertThat(result.failureCode()).isNull();
        verify(browser).service_click_active(anyDouble(), anyDouble());
        verify(browser).service_click_profile_action_active(List.of("私信", "发私信", "Message", "发消息"));
    }

    @Test
    void followAndDraftMarksSentWhenSendDmPrimitiveConfirmsSend() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinCommentItem comment = new DouyinCommentItem(
                "video",
                "comment",
                null,
                "",
                "https://www.douyin.com/user/MS4w",
                null,
                "不建议大家用易企秀，慢出心脏病了。",
                null,
                null,
                null,
                null);
        String profile = """
                {"ok":true,"url":"https://www.douyin.com/user/MS4w","title":"霞姐一百岁 - 抖音","viewport":{"w":1280,"h":800},"tree":"Heading[ref=ref_1, frame=0]: 霞姐一百岁 @{520,90 180x36}\\nStaticText[ref=ref_2, frame=0]: 已关注 @{912,224 86x36}\\nStaticText[ref=ref_3, frame=0]: 粉丝 32 @{520,150 90x24}"}
                """;
        String dmPage = """
                {"ok":true,"url":"https://www.douyin.com/im/霞姐一百岁","title":"私信 - 抖音","viewport":{"w":1280,"h":800},"tree":"StaticText[ref=ref_1, frame=0]: 私信 @{420,80 80x28}\\nStaticText[ref=ref_2, frame=0]: 发送消息 @{520,140 120x28}\\nTextbox[ref=ref_3, frame=0]:  @{520,700 360x44}"}
                """;

        when(browser.extension_browser_navigate(any(), any(), any())).thenReturn("{\"ok\":true}");
        when(browser.service_observe_active("all")).thenReturn(profile, dmPage, dmPage);
        when(browser.service_click_profile_action_active(List.of("私信", "发私信", "Message", "发消息")))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"label\":\"私信\"}}]}");
        when(browser.service_type_dm_draft_active("你好", true)).thenReturn("""
                {"ok":true,"results":[{"payload":{"draftTyped":true,"text":"你好","target":"dm_editable","sent":true,"sendTarget":"button"}}]}
                """);

        EngagementResult result = adapter.followAndDraft(comment, "你好", true);

        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.dmOpened()).isTrue();
        assertThat(result.draftTyped()).isTrue();
        assertThat(result.sent()).isTrue();
        assertThat(result.failureCode()).isNull();
        verify(browser).service_type_dm_draft_active("你好", true);
    }

    @Test
    void followAndDraftSendsExistingDraftAfterDmPrimitiveDetach() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinCommentItem comment = new DouyinCommentItem(
                "video",
                "comment",
                null,
                "霞姐一百岁",
                "https://www.douyin.com/user/MS4w",
                null,
                "不建议大家用易企秀，慢出心脏病了。",
                null,
                null,
                null,
                null);
        String profile = """
                {"ok":true,"url":"https://www.douyin.com/user/MS4w","title":"霞姐一百岁 - 抖音","viewport":{"w":1280,"h":800},"tree":"Heading[ref=ref_1, frame=0]: 霞姐一百岁 @{520,90 180x36}\\nStaticText[ref=ref_2, frame=0]: 已关注 @{912,224 86x36}\\nButton[ref=ref_3, frame=0]: 私信 @{1010,224 86x36}"}
                """;
        String dmPageWithDraft = """
                {"ok":true,"url":"https://www.douyin.com/im/霞姐一百岁","title":"私信 - 抖音","viewport":{"w":1280,"h":800},"tree":"StaticText[ref=ref_1, frame=0]: 私信 @{420,80 80x28}\\nStaticText[ref=ref_2, frame=0]: 发送消息 @{520,140 120x28}\\nTextbox[ref=ref_3, frame=0]: 你好 @{520,700 360x44}"}
                """;
        String dmPageNoDraft = """
                {"ok":true,"url":"https://www.douyin.com/im/霞姐一百岁","title":"私信 - 抖音","viewport":{"w":1280,"h":800},"tree":"StaticText[ref=ref_1, frame=0]: 私信 @{420,80 80x28}\\nStaticText[ref=ref_2, frame=0]: 发送消息 @{520,140 120x28}\\nTextbox[ref=ref_3, frame=0]:  @{520,700 360x44}"}
                """;

        when(browser.extension_browser_navigate(any(), any(), any())).thenReturn("{\"ok\":true}");
        when(browser.service_observe_active("all")).thenReturn(
                profile,
                dmPageWithDraft,
                dmPageWithDraft,
                dmPageWithDraft,
                dmPageNoDraft);
        when(browser.service_click_active(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_type_dm_draft_active("你好", true))
                .thenThrow(new RuntimeException("SESSION_DETACHED: target_closed"));
        when(browser.service_send_dm_active("你好")).thenReturn("""
                {"ok":true,"results":[{"payload":{"draftTyped":true,"sent":true,"sendTarget":"button"}}]}
                """);

        EngagementResult result = adapter.followAndDraft(comment, "你好", true);

        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.dmOpened()).isTrue();
        assertThat(result.draftTyped()).isTrue();
        assertThat(result.sent()).isTrue();
        assertThat(result.failureCode()).isNull();
        verify(browser).service_send_dm_active("你好");
        verify(browser, never()).service_type_active(eq("你好"), any());
    }

    @Test
    void followAndDraftClosesActiveEngagementTabWhenMainTabStillShowsVideo() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinCommentItem comment = new DouyinCommentItem(
                "video",
                "comment",
                null,
                "霞姐一百岁",
                "https://www.douyin.com/user/MS4w",
                null,
                "不建议大家用易企秀，慢出心脏病了。",
                null,
                null,
                null,
                null);
        String video = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/易企秀?modal_id=6689257066038676740","title":"易企秀 - 抖音","viewport":{"w":1280,"h":800},"tree":"Button[ref=ref_1, frame=0]: 评论 @{1180,320 80x44}"}
                """;
        String profile = """
                {"ok":true,"url":"https://www.douyin.com/user/MS4w","title":"霞姐一百岁 - 抖音","viewport":{"w":1280,"h":800},"tree":"Heading[ref=ref_1, frame=0]: 霞姐一百岁 @{520,90 180x36}\\nStaticText[ref=ref_2, frame=0]: 已关注 @{912,224 86x36}\\nButton[ref=ref_3, frame=0]: 私信 @{1010,224 86x36}"}
                """;
        String dmPage = """
                {"ok":true,"url":"https://www.douyin.com/im/霞姐一百岁","title":"私信 - 抖音","viewport":{"w":1280,"h":800},"tree":"StaticText[ref=ref_1, frame=0]: 私信 @{420,80 80x28}\\nStaticText[ref=ref_2, frame=0]: 发送消息 @{520,140 120x28}\\nTextbox[ref=ref_3, frame=0]: 你好 @{520,700 360x44}"}
                """;

        when(browser.service_observe_main("all")).thenReturn(video, video, video);
        when(browser.extension_browser_navigate(any(), any(), any())).thenReturn("{\"ok\":true}");
        when(browser.service_observe_active("all")).thenReturn(profile, dmPage, dmPage);
        when(browser.service_click_active(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_type_dm_draft_active("你好", false)).thenReturn("{\"ok\":true}");
        when(browser.service_close_tab_active()).thenReturn("{\"ok\":true}");

        EngagementResult result = adapter.followAndDraft(comment, "你好", false);

        assertThat(result.status()).isEqualTo("succeeded");
        verify(browser).service_close_tab_active();
        verify(browser, never()).service_press_key_active("Control+W");
        verify(browser, never()).extension_browser_navigate(
                eq("https://www.douyin.com/jingxuan/search/易企秀?modal_id=6689257066038676740"),
                eq("domcontentloaded"),
                any());
    }

    @Test
    void followAndDraftRetriesAuthorOpenAfterDeadlineExceeded() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinCommentItem comment = new DouyinCommentItem(
                "video",
                "comment",
                null,
                "霞姐一百岁",
                "https://www.douyin.com/user/MS4w",
                null,
                "不建议大家用易企秀，慢出心脏病了。",
                null,
                null,
                null,
                null);
        String profile = profilePage("霞姐一百岁");
        String dmPage = dmPage("霞姐一百岁", "你好");
        String video = videoPage();

        when(browser.service_open_author_from_comment_main(
                eq(comment.text()), eq(comment.authorName()), eq(comment.authorProfileUrl())))
                .thenReturn(
                        "{\"ok\":false,\"code\":\"DEADLINE_EXCEEDED\",\"message\":\"open author timed out\"}",
                        openAuthorResult(102L));
        when(browser.service_observe_tab(102L, "all")).thenReturn(profile, dmPage, dmPage);
        when(browser.service_click_profile_action_tab(102L, List.of("私信", "发私信", "Message", "发消息")))
                .thenReturn(okResult());
        when(browser.service_type_dm_draft_tab(102L, "你好", false)).thenReturn(okResult());
        when(browser.service_close_tab(102L)).thenReturn(okResult());
        when(browser.service_observe_main("all")).thenReturn(video);

        EngagementResult result = adapter.followAndDraft(comment, "你好", false);

        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.draftTyped()).isTrue();
        verify(browser, times(2)).service_open_author_from_comment_main(
                eq(comment.text()), eq(comment.authorName()), eq(comment.authorProfileUrl()));
        verify(browser).service_close_tab(102L);
    }

    @Test
    void followAndDraftClosesUnconfirmedAuthorTabBeforeRetrying() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinCommentItem comment = new DouyinCommentItem(
                "video",
                "comment",
                null,
                "霞姐一百岁",
                "https://www.douyin.com/user/MS4w",
                null,
                "不建议大家用易企秀，慢出心脏病了。",
                null,
                null,
                null,
                null);
        String profile = profilePage("霞姐一百岁");
        String dmPage = dmPage("霞姐一百岁", "你好");
        String video = videoPage();

        when(browser.service_open_author_from_comment_main(
                eq(comment.text()), eq(comment.authorName()), eq(comment.authorProfileUrl())))
                .thenReturn(openAuthorResult(101L), openAuthorResult(102L));
        when(browser.service_observe_tab(101L, "all"))
                .thenReturn("{\"ok\":false,\"code\":\"NO_TARGET_TAB\",\"message\":\"could not resolve tab_ref\"}");
        when(browser.service_close_tab(101L)).thenReturn(okResult());
        when(browser.service_observe_tab(102L, "all")).thenReturn(profile, dmPage, dmPage);
        when(browser.service_click_profile_action_tab(102L, List.of("私信", "发私信", "Message", "发消息")))
                .thenReturn(okResult());
        when(browser.service_type_dm_draft_tab(102L, "你好", false)).thenReturn(okResult());
        when(browser.service_close_tab(102L)).thenReturn(okResult());
        when(browser.service_observe_main("all")).thenReturn(video);

        EngagementResult result = adapter.followAndDraft(comment, "你好", false);

        assertThat(result.status()).isEqualTo("succeeded");
        verify(browser).service_close_tab(101L);
        verify(browser).service_close_tab(102L);
        verify(browser, times(2)).service_open_author_from_comment_main(
                eq(comment.text()), eq(comment.authorName()), eq(comment.authorProfileUrl()));
    }

    @Test
    void topSearchSubmitButtonRejectsAiSearchAndUsesHeaderSearchButton() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Button[ref=ref_1, frame=0]: AI搜索 @{680,280 120x36}
                Textbox[ref=ref_2, frame=0]: openclaw @{420,18 360x40}
                Button[ref=ref_3, frame=0]: 搜索 @{790,18 64x40}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point =
                adapter.findTopSearchSubmitButton(tree, new ExtensionDouyinBrowserAdapter.ClickPoint(600d, 38d));

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(822d);
        assertThat(point.y()).isEqualTo(38d);
    }

    private static String okResult() {
        return "{\"ok\":true}";
    }

    private static String openAuthorResult(long tabId) {
        return "{\"ok\":true,\"results\":[{\"payload\":{\"tabId\":" + tabId + "}}]}";
    }

    private static String videoPage() {
        return """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/易企秀?modal_id=6689257066038676740","title":"易企秀 - 抖音","viewport":{"w":1280,"h":800},"tree":"Button[ref=ref_1, frame=0]: 评论 @{1180,320 80x44}"}
                """;
    }

    private static String profilePage(String author) {
        return """
                {"ok":true,"url":"https://www.douyin.com/user/MS4w","title":"%s - 抖音","viewport":{"w":1280,"h":800},"tree":"Heading[ref=ref_1, frame=0]: %s @{520,90 180x36}\\nStaticText[ref=ref_2, frame=0]: 已关注 @{912,224 86x36}\\nStaticText[ref=ref_3, frame=0]: 粉丝 32 @{520,150 90x24}"}
                """.formatted(author, author);
    }

    private static String dmPage(String author, String draft) {
        return """
                {"ok":true,"url":"https://www.douyin.com/im/%s","title":"私信 - 抖音","viewport":{"w":1280,"h":800},"tree":"StaticText[ref=ref_1, frame=0]: 私信 @{420,80 80x28}\\nStaticText[ref=ref_2, frame=0]: 发送消息 @{520,140 120x28}\\nTextbox[ref=ref_3, frame=0]: %s @{520,700 360x44}"}
                """.formatted(author, draft);
    }

    @Test
    void searchBoxLocatorAcceptsCustomTopSearchControlRoles() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Button[ref=ref_1, frame=0]: AI搜索 @{650,300 120x36}
                Generic[ref=ref_2, frame=0]: 搜索你感兴趣的内容 @{420,18 360x40}
                Button[ref=ref_3, frame=0]: 搜索 @{790,18 64x40}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point = adapter.findDouyinSearchBoxPoint(tree);

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(600d);
        assertThat(point.y()).isEqualTo(38d);
    }

    @Test
    void searchBoxLocatorAcceptsIndentedSearchInputFromBanner() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Link[ref=ref_24, frame=0]: 搜索 @{32,150 112x24}
                Link[ref=ref_30, frame=0]: 朋友 @{32,257 112x24}
                Banner[ref=ref_51, frame=0] @{160,0 1120x56}
                  Textbox[ref=ref_52, frame=0]: 搜索你感兴趣的内容 @{454,9 315x38}
                  Button[ref=ref_53, frame=0]: 搜索 @{784,9 80x38}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point = adapter.findDouyinSearchBoxPoint(tree);

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(611.5d);
        assertThat(point.y()).isEqualTo(28d);
    }

    @Test
    void searchVerificationRejectsAiSearchPage() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/aisearch",
                "抖音 AI 抖音 - 有问题来抖音问 AI",
                """
                        Textbox[ref=ref_1, frame=0]: openclaw @{420,18 360x40}
                        Button[ref=ref_2, frame=0]: AI搜索 @{790,18 64x40}
                        StaticText[ref=ref_3, frame=0]: openclaw @{420,92 200x32}
                        """,
                1280,
                800,
                "",
                "");

        assertThat(adapter.searchVerified(observation, "openclaw")).isFalse();
    }

    @Test
    void searchVerificationRejectsBareSearchShellUntilResultsOrFiltersAreVisible() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan/search/openclaw",
                "发现更多精彩视频 - 抖音搜索",
                """
                        Searchbox[ref=ref_1, frame=0]: openclaw @{420,18 360x40}
                        Link[ref=ref_2, frame=0]: 精选 @{20,92 80x32}
                        """,
                1280,
                800,
                "",
                "");

        assertThat(adapter.searchVerified(observation, "openclaw")).isFalse();
    }

    @Test
    void searchVerificationAcceptsEncodedJingxuanSearchUrlForChineseKeyword() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan/search/ai%E6%95%B0%E5%AD%97%E5%8C%96%E8%BD%AC%E5%9E%8B?type=general",
                "发现更多精彩视频 - 抖音搜索",
                """
                        Searchbox[ref=ref_1, frame=0]: ai数字化转型 @{420,18 360x40}
                        Text[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}
                        Text[ref=ref_3, frame=0]: 企业AI数字化转型怎么做 #ai数字化转型 @{203,430 215x91}
                        Text[ref=ref_4, frame=0]: 12.4万 @{220,407 50x19}
                        """,
                1920,
                900,
                "",
                "");

        assertThat(adapter.searchVerified(observation, "ai数字化转型")).isTrue();
    }

    @Test
    void searchVerificationRejectsHomeFeedEvenWhenKeywordVideosAreVisible() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan",
                "抖音精选电脑版 - 抖音旗下优质视频平台",
                """
                        Textbox[ref=ref_1, frame=0]: 搜索你感兴趣的内容 @{454,9 315x38}
                        Text[ref=ref_2, frame=0]: 花60天时间，研究的openclaw小龙虾做的AI一人公司模式。#openclaw @{250,450 215x91}
                        Text[ref=ref_3, frame=0]: 50.2万 @{250,420 52x19}
                        Text[ref=ref_4, frame=0]: 谁说openclaw没有实际的落地应用了 #openclaw @{610,450 215x91}
                        Text[ref=ref_5, frame=0]: 66.2万 @{610,420 52x19}
                        """,
                1920,
                900,
                "",
                "");

        assertThat(adapter.searchVerified(observation, "openclaw")).isFalse();
    }

    @Test
    void reusableSearchResultsRejectsOpenVideoModalState() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan/search/openclaw?modal_id=1111111111111",
                "发现更多精彩视频 - 抖音搜索",
                """
                        Searchbox[ref=ref_1, frame=0]: openclaw @{420,18 360x40}
                        Tab[ref=ref_2, frame=0]: 评论 @{860,88 72x28}
                        Tab[ref=ref_3, frame=0]: 详情 @{780,88 72x28}
                        StaticText[ref=ref_4, frame=0]: 回复 @{860,260 80x24}
                        """,
                1280,
                800,
                "",
                "");

        assertThat(adapter.searchVerified(observation, "openclaw")).isFalse();
        assertThat(adapter.canReuseSearchResultsForSorting(observation, "openclaw")).isFalse();
    }

    @Test
    void reusableSearchResultsAcceptsPlainSearchPage() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan/search/openclaw",
                "发现更多精彩视频 - 抖音搜索",
                """
                        Searchbox[ref=ref_1, frame=0]: openclaw @{420,18 360x40}
                        Generic[ref=ref_2, frame=0]: 筛选 @{430,92 56x28}
                        """,
                1280,
                800,
                "",
                "");

        assertThat(adapter.canReuseSearchResultsForSorting(observation, "openclaw")).isTrue();
    }

    @Test
    void searchTextEnteredRequiresKeywordInsideSearchLikeControl() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan",
                "抖音-记录美好生活",
                """
                        Searchbox[ref=ref_1, frame=0]: openclaw @{420,18 360x40}
                        Button[ref=ref_2, frame=0]: AI搜索 @{790,18 64x40}
                        """,
                1280,
                800,
                "",
                "");

        assertThat(adapter.searchTextEntered(observation, "openclaw")).isTrue();
    }

    @Test
    void searchTextEnteredAcceptsKeywordRenderedAsTopSearchText() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan",
                "抖音-记录美好生活",
                """
                        Searchbox[ref=ref_1, frame=0]: 搜索你感兴趣的内容 @{420,18 360x40}
                        Text[ref=ref_2, frame=0]: openclaw @{456,24 90x28}
                        Button[ref=ref_3, frame=0]: AI搜索 @{790,18 64x40}
                        """,
                1280,
                800,
                "",
                "");

        assertThat(adapter.searchTextEntered(observation, "openclaw")).isTrue();
    }

    @Test
    void searchTextEnteredRejectsKeywordOutsideInput() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan",
                "抖音-记录美好生活",
                """
                        Searchbox[ref=ref_1, frame=0]: 搜索你感兴趣的内容 @{420,18 360x40}
                        StaticText[ref=ref_2, frame=0]: openclaw 推荐内容 @{520,220 260x40}
                        Button[ref=ref_3, frame=0]: AI搜索 @{790,18 64x40}
                        """,
                1280,
                800,
                "",
                "");

        assertThat(adapter.searchTextEntered(observation, "openclaw")).isFalse();
    }

    @Test
    void filterTriggerLocatorAcceptsCustomDouyinRoles() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                StaticText[ref=ref_1, frame=0]: 综合 @{310,90 56x28}
                Generic[ref=ref_2, frame=0]: 筛选 @{430,92 56x28}
                StaticText[ref=ref_3, frame=0]: openclaw 教程获得最多点赞 @{500,360 260x42}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point = adapter.findFilterTriggerPoint(tree);

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(458d);
        assertThat(point.y()).isEqualTo(106d);
    }

    @Test
    void filterTriggerClickPointsAreDerivedFromObservedElementBounds() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Text[ref=ref_1, frame=0]: 筛选 @{686,66 56x26}
                """;

        ExtensionDouyinBrowserAdapter.TreeLine line = adapter.findFilterTriggerLine(tree);
        java.util.List<ExtensionDouyinBrowserAdapter.ClickPoint> points =
                adapter.filterTriggerPoints(line, 1280, 800);

        assertThat(points).hasSizeGreaterThanOrEqualTo(3);
        assertThat(points.getFirst().x()).isEqualTo(714d);
        assertThat(points).anySatisfy(point -> assertThat(point.x()).isGreaterThan(730d));
    }

    @Test
    void sortOptionLocatorIgnoresSearchResultTextAndAcceptsMenuRows() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Link[ref=ref_1, frame=0]: openclaw 获得最多点赞的视频合集 @{530,330 260x42}
                StaticText[ref=ref_2, frame=0]: 排序依据 @{430,124 72x24}
                Generic[ref=ref_3, frame=0]: 最多点赞 @{430,172 84x28}
                StaticText[ref=ref_4, frame=0]: 最新发布 @{430,216 84x28}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point =
                adapter.findSortOptionPoint(tree, java.util.List.of("最多点赞", "点赞最多"));

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(472d);
        assertThat(point.y()).isEqualTo(186d);
    }

    @Test
    void videoCandidateLocatorPrefersResultCardAndIgnoresRelatedSearchSuggestions() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Text[ref=ref_181, frame=0]: 筛选 @{1186,66 36x26}
                Image[ref=ref_189, frame=0] @{191,173 245x327}
                Text[ref=ref_195, frame=0]: 全网都在养的“龙虾”，它能做什么？#记者实测OpenClaw温馨提示 @{203,512 215x91}
                Image[ref=ref_334, frame=0] @{753,679 16x16}
                Text[ref=ref_336, frame=0]: openclaw安装教程 @{775,677 118x20}
                Text[ref=ref_340, frame=0]: 飞书openclaw @{775,724 90x20}
                """;

        java.util.List<ExtensionDouyinBrowserAdapter.ClickPoint> points =
                adapter.findVideoCandidatePoints(tree, 1280, 575);

        assertThat(points).isNotEmpty();
        assertThat(points.getFirst().x()).isEqualTo(313.5d);
        assertThat(points.getFirst().y()).isEqualTo(336.5d);
    }

    @Test
    void mostLikedVerificationUsesFirstVisibleRowNotWholePageMaximum() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw",
                "发现更多精彩视频 - 抖音搜索",
                """
                        Searchbox[ref=ref_1, frame=0]: openclaw @{447,9 312x38}
                        Text[ref=ref_2, frame=0]: 筛选 @{1186,66 36x26}
                        Text[ref=ref_12, frame=0]: 最多点赞已选 @{430,118 120x28}
                        Image[ref=ref_3, frame=0] @{191,173 245x327}
                        Text[ref=ref_4, frame=0]: 7.2万 @{222,470 42x19}
                        Text[ref=ref_5, frame=0]: OpenClaw 第二条但视觉第一 @{203,512 215x91}
                        Image[ref=ref_6, frame=0] @{462,173 245x327}
                        Text[ref=ref_7, frame=0]: 55.7万 @{493,470 42x19}
                        Text[ref=ref_8, frame=0]: 全网都在养的“龙虾”，它能做什么？#记者实测OpenClaw @{474,512 215x91}
                        """,
                1280,
                575,
                "",
                "");

        assertThat(adapter.mostLikedSortVerified(observation, "openclaw")).isFalse();
        assertThat(adapter.findVideoCandidatePoints(observation.tree(), 1280, 575).getFirst().x())
                .isEqualTo(313.5d);
    }

    @Test
    void mostLikedVerificationAcceptsFirstRowSortedEvenWhenLowerRowHasHigherStaleCount() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw",
                "发现更多精彩视频 - 抖音搜索",
                """
                        Searchbox[ref=ref_1, frame=0]: openclaw @{447,9 312x38}
                        Text[ref=ref_2, frame=0]: 筛选 @{1186,66 36x26}
                        Text[ref=ref_12, frame=0]: 最多点赞已选 @{430,118 120x28}
                        Image[ref=ref_3, frame=0] @{191,173 245x327}
                        Text[ref=ref_4, frame=0]: 39.6万 @{222,470 42x19}
                        Text[ref=ref_5, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理 @{203,512 215x91}
                        Image[ref=ref_6, frame=0] @{462,173 245x327}
                        Text[ref=ref_7, frame=0]: 15.2万 @{493,470 42x19}
                        Text[ref=ref_8, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{474,512 215x91}
                        Image[ref=ref_9, frame=0] @{191,820 245x327}
                        Text[ref=ref_10, frame=0]: 99.9万 @{222,1118 42x19}
                        Text[ref=ref_11, frame=0]: 下方旧推荐 OpenClaw 视频 @{203,1160 215x91}
                        """,
                1280,
                575,
                "",
                "");

        assertThat(adapter.mostLikedSortVerified(observation, "openclaw")).isTrue();
    }

    @Test
    void mostLikedVerificationAcceptsSortedFirstSequenceWhenA11yYValuesDrift() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw",
                "发现更多精彩视频 - 抖音搜索",
                """
                        Searchbox[ref=ref_1, frame=0]: openclaw @{454,9 315x38}
                        Text[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}
                        Text[ref=ref_3, frame=0]: 最多点赞已选 @{430,118 120x28}
                        Text[ref=ref_4, frame=0]: 39.6万 @{300,447 52x19}
                        Text[ref=ref_5, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理 @{203,412 215x91}
                        Text[ref=ref_6, frame=0]: 15.2万 @{616,436 52x19}
                        Text[ref=ref_7, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{520,405 215x91}
                        Text[ref=ref_8, frame=0]: 9.8万 @{928,424 52x19}
                        Text[ref=ref_9, frame=0]: OpenClaw(Clawdbot) 海量全玩法攻略 本地部署 @{835,394 215x91}
                        Text[ref=ref_10, frame=0]: 3万 @{1244,424 40x19}
                        Text[ref=ref_11, frame=0]: 小龙虾OpenClaw便携版U盘安装教程 @{1150,394 215x91}
                        """,
                1920,
                855,
                "",
                "");

        assertThat(adapter.mostLikedSortVerified(observation, "openclaw")).isTrue();
    }

    @Test
    void explicitMostLikedSelectionAcceptsSortedSequenceWithoutSelectedSignal() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        DouyinBrowserAdapter.BrowserObservation observation = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/jingxuan/search/openclaw?aid=80a75691-f295-4754-8c10-e36c67be6661&type=general",
                "抖音精选电脑版 - 抖音旗下优质视频平台",
                """
                        Textbox[ref=ref_18, frame=0]: 搜索你感兴趣的内容 @{454,9 315x38}
                        Text[ref=ref_36, frame=0]: 筛选 @{1810,66 54x26}
                        Text[ref=ref_101, frame=0]: 39.6万 @{300,447 52x19}
                        Text[ref=ref_102, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理，它可以连接飞书等聊天工具，帮你处理各类任务。 #openclaw#智能体#moltbot#编程#AI助理 @{203,412 215x91}
                        Text[ref=ref_103, frame=0]: 15.2万 @{616,436 52x19}
                        Text[ref=ref_104, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ #openclaw #智能体 #人工智能 #AI新星计划 #抖音年味新知贺岁 @{520,405 215x91}
                        Text[ref=ref_105, frame=0]: 9.8万 @{928,424 52x19}
                        Text[ref=ref_106, frame=0]: OpenClaw(Clawdbot) 海量全玩法攻略 本地部署，国内网络友好使用 #AI新星计划 #科技 #计算机 #编程 #教程 @{835,394 215x91}
                        Text[ref=ref_107, frame=0]: 3万 @{1244,424 40x19}
                        Text[ref=ref_108, frame=0]: 小龙虾OpenClaw便携版U盘？58块走哪AI到哪？#明知山有虎 #老张是大佬 #小龙虾 #龙虾u盘 #openclaw @{1150,394 215x91}
                        """,
                1920,
                855,
                "",
                "");

        assertThat(adapter.mostLikedSortVerifiedAfterExplicitSelection(observation, "openclaw")).isTrue();
    }

    @Test
    void applySortDoesNotSucceedWhenMostLikedClickedButNoVideoCandidatesLoaded() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String searchShell = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/openclaw?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"Textbox[ref=ref_1, frame=0]: 搜索你感兴趣的内容 @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nGeneric[ref=ref_3, frame=0]: 最多点赞 @{430,172 84x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(searchShell);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_click_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");

        assertThatThrownBy(() -> adapter.applySort(new DouyinLeadAcquisitionInput(
                "openclaw",
                "most_liked",
                1,
                List.of(),
                "你好",
                false)))
                .isInstanceOf(DouyinBrowserException.class)
                .extracting(error -> ((DouyinBrowserException) error).code())
                .isEqualTo("SORT_NOT_CONFIRMED");
    }

    @Test
    void visualVideoOrderIsRowMajorLeftToRightAfterSort() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Text[ref=ref_1, frame=0]: 筛选 @{1186,66 36x26}
                Image[ref=ref_2, frame=0] @{190,170 245x327}
                Text[ref=ref_3, frame=0]: 7.2万 @{220,468 42x19}
                Text[ref=ref_4, frame=0]: 左侧第一条 OpenClaw 视频 @{203,510 215x91}
                Image[ref=ref_5, frame=0] @{462,168 245x327}
                Text[ref=ref_6, frame=0]: 55.7万 @{493,468 42x19}
                Text[ref=ref_7, frame=0]: 右侧第二条 OpenClaw 视频 @{474,510 215x91}
                Image[ref=ref_8, frame=0] @{190,620 245x327}
                Text[ref=ref_9, frame=0]: 99.9万 @{220,918 42x19}
                Text[ref=ref_10, frame=0]: 第二行第一条 OpenClaw 视频 @{203,960 215x91}
                """;

        java.util.List<ExtensionDouyinBrowserAdapter.ClickPoint> points =
                adapter.findVideoCandidatePoints(tree, 1280, 855);

        assertThat(points).isNotEmpty();
        assertThat(points.getFirst().x()).isEqualTo(312.5d);
        assertThat(points.get(1).x()).isEqualTo(584.5d);
    }

    @Test
    void visualVideoOrderKeepsLeftmostCardOnWideViewport() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Text[ref=ref_1, frame=0]: 筛选 @{1810,66 54x26}
                Image[ref=ref_2, frame=0] @{190,170 245x327}
                Text[ref=ref_3, frame=0]: 55.7万 @{220,468 42x19}
                Text[ref=ref_4, frame=0]: 全网都在养的“龙虾”，它能做什么？#记者实测OpenClaw @{203,510 215x91}
                Image[ref=ref_5, frame=0] @{462,168 245x327}
                Text[ref=ref_6, frame=0]: 7.2万 @{493,468 42x19}
                Text[ref=ref_7, frame=0]: 第二个 OpenClaw 视频 @{474,510 215x91}
                """;

        java.util.List<ExtensionDouyinBrowserAdapter.ClickPoint> points =
                adapter.findVideoCandidatePoints(tree, 1920, 855);

        assertThat(points).isNotEmpty();
        assertThat(points.getFirst().x()).isEqualTo(312.5d);
    }

    @Test
    void visualVideoOrderKeepsActualMostLikedFirstCardTitle() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Text[ref=ref_1, frame=0]: 筛选 @{1810,66 54x26}
                Image[ref=ref_2, frame=0] @{190,158 245x327}
                Text[ref=ref_3, frame=0]: 39.6万 @{220,466 50x19}
                Text[ref=ref_4, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理，它可以连接飞书等聊天工具，帮你处理各类任务。 #openclaw#智能体#moltbot#编程#AI助理 @{203,510 215x91}
                Image[ref=ref_5, frame=0] @{462,158 245x327}
                Text[ref=ref_6, frame=0]: 15.2万 @{493,466 50x19}
                Text[ref=ref_7, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{474,510 215x91}
                """; 

        java.util.List<ExtensionDouyinBrowserAdapter.VideoResultTarget> targets =
                adapter.visualOrderVideoTargets(tree, 1920, 855);

        assertThat(targets).isNotEmpty();
        assertThat(targets.getFirst().title()).contains("我有自己的AI助理啦");
        assertThat(targets.getFirst().clickLine().point().x()).isEqualTo(312.5d);
    }

    @Test
    void visualVideoOrderDoesNotHardFilterLeftEdgeFirstCard() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Text[ref=ref_1, frame=0]: 筛选 @{1810,66 54x26}
                Image[ref=ref_2, frame=0] @{118,158 245x327}
                Text[ref=ref_3, frame=0]: 39.6万 @{140,466 50x19}
                Text[ref=ref_4, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理，它可以连接飞书等聊天工具，帮你处理各类任务。 #openclaw#智能体#moltbot#编程#AI助理 @{126,510 215x91}
                Image[ref=ref_5, frame=0] @{462,158 245x327}
                Text[ref=ref_6, frame=0]: 15.2万 @{493,466 50x19}
                Text[ref=ref_7, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{474,510 215x91}
                """;

        java.util.List<ExtensionDouyinBrowserAdapter.VideoResultTarget> targets =
                adapter.visualOrderVideoTargets(tree, 1920, 855);

        assertThat(targets).isNotEmpty();
        assertThat(targets.getFirst().title()).contains("我有自己的AI助理啦");
        assertThat(targets.getFirst().clickLine().point().x()).isEqualTo(240.5d);
    }

    @Test
    void a11yVideoFallbackClicksCoverInsteadOfTitleText() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Text[ref=ref_1, frame=0]: 筛选 @{1810,66 54x26}
                Image[ref=ref_2, frame=0] @{190,158 245x327}
                Text[ref=ref_3, frame=0]: 39.6万 @{220,466 50x19}
                Text[ref=ref_4, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理，它可以连接飞书等聊天工具，帮你处理各类任务。 #openclaw#智能体#moltbot#编程#AI助理 @{203,510 215x91}
                Image[ref=ref_5, frame=0] @{462,158 245x327}
                Text[ref=ref_6, frame=0]: 15.2万 @{493,466 50x19}
                Text[ref=ref_7, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{474,510 215x91}
                """;

        java.util.List<ExtensionDouyinBrowserAdapter.ClickPoint> points =
                adapter.findVideoCandidatePoints(tree, 1920, 855);

        assertThat(points).isNotEmpty();
        assertThat(points.getFirst().x()).isEqualTo(312.5d);
        assertThat(points.getFirst().y()).isEqualTo(321.5d);
    }

    @Test
    void commentTriggerInfersBubbleAboveSecondRightRailNumberWhenLabelsAreIconOnly() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Image[ref=ref_1, frame=0]: 作者头像 @{1960,84 56x56}
                Text[ref=ref_2, frame=0]: 2.3万 @{1966,276 54x28}
                Text[ref=ref_3, frame=0]: 129 @{1974,402 36x28}
                Text[ref=ref_4, frame=0]: 1283 @{1968,528 48x28}
                Text[ref=ref_5, frame=0]: 1064 @{1968,642 48x28}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point =
                adapter.findCommentTriggerPoint(tree, 2048, 720);

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(1992.0d);
        assertThat(point.y()).isBetween(342.0d, 372.0d);
    }

    @Test
    void openVideoA11yFallbackInfersCoverPointWhenOnlyTitleIsClickableEvidence() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        when(browser.service_observe_main("all")).thenReturn(
                """
                        {"ok":true,"url":"https://www.douyin.com/jingxuan/search/openclaw?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: openclaw @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nText[ref=ref_3, frame=0]: 39.6万 @{220,407 50x19}\\nText[ref=ref_4, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理，它可以连接飞书等聊天工具，帮你处理各类任务。 #openclaw#智能体#moltbot#编程#AI助理 @{203,430 215x91}\\nText[ref=ref_5, frame=0]: 15.2万 @{493,407 50x19}\\nText[ref=ref_6, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{474,430 215x91}"}
                        """,
                """
                        {"ok":true,"url":"https://www.douyin.com/jingxuan/search/openclaw?modal_id=1111111111111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Text[ref=ref_1, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理 @{820,120 520x40}\\nButton[ref=ref_2, frame=0]: 评论 @{1180,320 80x44}"}
                        """);
        when(browser.service_register_region_main(
                "douyin.search_results", 0.0d, 0.0d, 1920.0d, 900.0d, "search-results-dom"))
                .thenReturn("{\"ok\":true}");
        when(browser.service_extract_region_main("douyin.search_results", 80)).thenReturn(
                "{\"ok\":true,\"results\":[{\"kind\":\"extract_region\",\"payload\":{\"regionKey\":\"douyin.search_results\",\"items\":[]}}]}");
        when(browser.service_click_main(310.5d, 225.0d)).thenReturn("{\"ok\":true}");
        when(browser.extension_browser_wait("time", 1800L, null, null, null)).thenReturn("{\"ok\":true}");

        DouyinBrowserAdapter.BrowserObservation opened = adapter.openVideo(0);

        assertThat(opened.code()).isEqualTo("VIDEO_TARGET");
        assertThat(opened.message()).contains("source=a11y_fallback");
        verify(browser).service_click_main(310.5d, 225.0d);
        verify(browser, never()).service_click_main(310.5d, 475.5d);
    }

    @Test
    void openVideoUsesArrowDownWhenCurrentVideoIsAlreadyOpen() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String currentWithComments = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/易企秀?modal_id=1111111111111","title":"第一个视频 - 抖音","viewport":{"w":1280,"h":800},"tree":"Text[ref=ref_1, frame=0]: 全部评论(90) @{730,80 180x32}\\nText[ref=ref_2, frame=0]: 暂时没有更多评论 @{820,720 180x28}"}
                """;
        String currentClosed = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/易企秀?modal_id=1111111111111","title":"第一个视频 - 抖音","viewport":{"w":1280,"h":800},"tree":"Button[ref=ref_1, frame=0]: 评论 @{1180,320 80x44}"}
                """;
        String secondVideo = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/易企秀?modal_id=2222222222222","title":"第二个视频 - 抖音","viewport":{"w":1280,"h":800},"tree":"Button[ref=ref_1, frame=0]: 评论 @{1180,320 80x44}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(currentWithComments, currentClosed, secondVideo);
        when(browser.service_press_key_main("x")).thenReturn("{\"ok\":true}");
        when(browser.service_press_key_main("ArrowDown")).thenReturn("{\"ok\":true}");

        DouyinBrowserAdapter.BrowserObservation opened = adapter.openVideo(1);

        assertThat(opened.code()).isEqualTo("VIDEO_TARGET");
        assertThat(opened.url()).contains("2222222222222");
        assertThat(opened.message()).contains("keyboard_arrow_down:index=1");
        verify(browser).service_press_key_main("x");
        verify(browser).service_press_key_main("ArrowDown");
        verify(browser, never()).service_click_main(anyDouble(), anyDouble());
    }

    @Test
    void openVideoUsesSortedSnapshotWhenLaterObservationDropsFirstCard() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String searchPage = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/openclaw?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: openclaw @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nText[ref=ref_3, frame=0]: 39.6万 @{220,407 50x19}\\nText[ref=ref_4, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理，它可以连接飞书等聊天工具，帮你处理各类任务。 #openclaw#智能体#moltbot#编程#AI助理 @{203,430 215x91}\\nText[ref=ref_5, frame=0]: 15.2万 @{493,407 50x19}\\nText[ref=ref_6, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{474,430 215x91}"}
                """;
        String filterPanel = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/openclaw?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: openclaw @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nGeneric[ref=ref_3, frame=0]: 最多点赞 @{430,172 84x28}\\nText[ref=ref_4, frame=0]: 39.6万 @{220,407 50x19}\\nText[ref=ref_5, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理，它可以连接飞书等聊天工具，帮你处理各类任务。 #openclaw#智能体#moltbot#编程#AI助理 @{203,430 215x91}\\nText[ref=ref_6, frame=0]: 15.2万 @{493,407 50x19}\\nText[ref=ref_7, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{474,430 215x91}"}
                """;
        String sortedSnapshot = searchPage;
        String laterObservationMissingFirstCard = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/openclaw?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: openclaw @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nText[ref=ref_3, frame=0]: 15.2万 @{493,407 50x19}\\nText[ref=ref_4, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{474,430 215x91}"}
                """;
        String openedFirstVideo = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/openclaw?modal_id=7610826082237350470","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Text[ref=ref_1, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理 @{820,120 520x40}\\nButton[ref=ref_2, frame=0]: 评论 @{1180,320 80x44}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(
                searchPage,
                filterPanel,
                sortedSnapshot,
                laterObservationMissingFirstCard,
                openedFirstVideo);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_click_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_register_region_main(
                "douyin.search_results", 0.0d, 0.0d, 1920.0d, 900.0d, "search-results-dom"))
                .thenReturn("{\"ok\":true}");
        when(browser.service_extract_region_main("douyin.search_results", 80)).thenReturn(
                "{\"ok\":true,\"results\":[{\"kind\":\"extract_region\",\"payload\":{\"regionKey\":\"douyin.search_results\",\"items\":[]}}]}");

        adapter.applySort(new DouyinLeadAcquisitionInput(
                "openclaw",
                "most_liked",
                1,
                List.of(),
                "你好",
                false));
        DouyinBrowserAdapter.BrowserObservation opened = adapter.openVideo(0);

        assertThat(opened.code()).isEqualTo("VIDEO_TARGET");
        assertThat(opened.message()).contains("using sorted_snapshot");
        assertThat(opened.message()).contains("我有自己的AI助理啦");
        verify(browser).service_click_main(472.0d, 186.0d);
        verify(browser).service_click_main(310.5d, 225.0d);
        verify(browser, never()).service_click_main(581.5d, 270.75d);
    }

    @Test
    void applySortStillClicksMostLikedWhenInitialResultsOnlyLookSorted() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String searchPage = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/ai数字化转型?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: ai数字化转型 @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nText[ref=ref_3, frame=0]: 39.6万 @{220,407 50x19}\\nText[ref=ref_4, frame=0]: 第一条 ai数字化转型 视频 @{203,430 215x91}\\nText[ref=ref_5, frame=0]: 15.2万 @{493,407 50x19}\\nText[ref=ref_6, frame=0]: 第二条 ai数字化转型 视频 @{474,430 215x91}\\nText[ref=ref_7, frame=0]: 9.8万 @{766,407 50x19}\\nText[ref=ref_8, frame=0]: 第三条 ai数字化转型 视频 @{747,430 215x91}"}
                """;
        String filterPanel = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/ai数字化转型?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: ai数字化转型 @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nGeneric[ref=ref_9, frame=0]: 最多点赞 @{430,172 84x28}\\nText[ref=ref_3, frame=0]: 39.6万 @{220,407 50x19}\\nText[ref=ref_4, frame=0]: 第一条 ai数字化转型 视频 @{203,430 215x91}\\nText[ref=ref_5, frame=0]: 15.2万 @{493,407 50x19}\\nText[ref=ref_6, frame=0]: 第二条 ai数字化转型 视频 @{474,430 215x91}\\nText[ref=ref_7, frame=0]: 9.8万 @{766,407 50x19}\\nText[ref=ref_8, frame=0]: 第三条 ai数字化转型 视频 @{747,430 215x91}"}
                """;
        String sortedSelected = filterPanel.replace("最多点赞", "最多点赞已选");
        when(browser.service_observe_main("all")).thenReturn(searchPage, filterPanel, sortedSelected);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_click_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_register_region_main(
                "douyin.search_results", 0.0d, 0.0d, 1920.0d, 900.0d, "search-results-dom"))
                .thenReturn("{\"ok\":true}");
        when(browser.service_extract_region_main("douyin.search_results", 80)).thenReturn(
                "{\"ok\":true,\"results\":[{\"kind\":\"extract_region\",\"payload\":{\"regionKey\":\"douyin.search_results\",\"items\":[]}}]}");

        DouyinBrowserAdapter.BrowserObservation sorted = adapter.applySort(new DouyinLeadAcquisitionInput(
                "ai数字化转型",
                "most_liked",
                1,
                List.of(),
                "你好",
                false));

        assertThat(sorted.tree()).contains("最多点赞已选");
        verify(browser).service_click_main(472.0d, 186.0d);
    }

    @Test
    void applySortClicksLatestWhenLatestSortRequested() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String searchPage = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/易企秀?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: 易企秀 @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nText[ref=ref_20, frame=0]: 综合排序 最新发布 最多点赞 @{390,118 260x28}\\nText[ref=ref_3, frame=0]: 39.6万 @{220,407 50x19}\\nText[ref=ref_4, frame=0]: 第一条 易企秀 视频 @{203,430 215x91}"}
                """;
        String filterPanel = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/易企秀?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: 易企秀 @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nGeneric[ref=ref_9, frame=0]: 最新发布 @{430,202 84x28}\\nText[ref=ref_3, frame=0]: 39.6万 @{220,407 50x19}\\nText[ref=ref_4, frame=0]: 第一条 易企秀 视频 @{203,430 215x91}"}
                """;
        String latestSelected = filterPanel.replace("最新发布", "最新发布已选");
        when(browser.service_observe_main("all")).thenReturn(searchPage, filterPanel, latestSelected);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_click_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_register_region_main(
                "douyin.search_results", 0.0d, 0.0d, 1920.0d, 900.0d, "search-results-dom"))
                .thenReturn("{\"ok\":true}");
        when(browser.service_extract_region_main("douyin.search_results", 80)).thenReturn(
                "{\"ok\":true,\"results\":[{\"kind\":\"extract_region\",\"payload\":{\"regionKey\":\"douyin.search_results\",\"items\":[]}}]}");

        DouyinBrowserAdapter.BrowserObservation sorted = adapter.applySort(new DouyinLeadAcquisitionInput(
                "易企秀",
                "latest",
                1,
                List.of(),
                "你好",
                false));

        assertThat(sorted.tree()).contains("最新发布已选");
        verify(browser).service_click_main(472.0d, 216.0d);
    }

    @Test
    void latestSortPointIgnoresCombinedSortSummaryLine() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Text[ref=ref_1, frame=0]: 综合排序 最新发布 最多点赞 @{390,118 260x28}
                Generic[ref=ref_2, frame=0]: 最新发布 @{430,202 84x28}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point =
                adapter.findSortOptionPoint(tree, java.util.List.of("最新发布", "发布时间", "按时间", "按发布时间"));

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(472.0d);
        assertThat(point.y()).isEqualTo(216.0d);
    }

    @Test
    void applySortAcceptsStableResultsAfterClickWhenDouyinHidesSelectedState() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String searchPage = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/ai数字化转型?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: ai数字化转型 @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nText[ref=ref_3, frame=0]: 播放 @{220,407 50x19}\\nText[ref=ref_4, frame=0]: ai数字化转型 相关视频 @{203,430 215x91}"}
                """;
        String filterPanel = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/ai数字化转型?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: ai数字化转型 @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nGeneric[ref=ref_9, frame=0]: 最多点赞 @{430,172 84x28}\\nText[ref=ref_3, frame=0]: 播放 @{220,407 50x19}\\nText[ref=ref_4, frame=0]: ai数字化转型 相关视频 @{203,430 215x91}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(searchPage, filterPanel, searchPage);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_click_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");

        DouyinBrowserAdapter.BrowserObservation sorted = adapter.applySort(new DouyinLeadAcquisitionInput(
                "ai数字化转型",
                "most_liked",
                1,
                List.of(),
                "你好",
                false));

        assertThat(sorted.tree()).contains("播放");
        assertThat(sorted.tree()).doesNotContain("最多点赞已选");
        assertThat(sorted.url()).doesNotContain("sort");
        verify(browser).service_click_main(472.0d, 186.0d);
    }

    @Test
    void unconfirmedOpenClawSortFailsWithoutDirectVideoNavigation() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String searchPage = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/openclaw?type=general","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: openclaw @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nText[ref=ref_3, frame=0]: 15.2万 @{128,407 50x19}\\nText[ref=ref_4, frame=0]: 一个视频搞懂OpenClaw！ 技术圈爆火的小龙虾到底是何方神圣？ @{30,430 215x91}\\nText[ref=ref_5, frame=0]: 39.6万 @{355,407 50x19}\\nText[ref=ref_6, frame=0]: 我有自己的AI助理啦！ OpenClaw是一款运行在你个人电脑上的开源AI助理，它可以连接飞书等聊天工具，帮你处理各类任务。 #openclaw#智能体#moltbot#编程#AI助理 @{278,430 215x91}"}
                """;
        String filterPanel = searchPage.replace(
                "Text[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}",
                "Text[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}\\nGeneric[ref=ref_3, frame=0]: 最多点赞 @{430,172 84x28}");
        when(browser.service_observe_main("all")).thenReturn(filterPanel);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_click_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_register_region_main(
                "douyin.search_results", 0.0d, 0.0d, 1920.0d, 900.0d, "search-results-dom"))
                .thenReturn("{\"ok\":true}");
        when(browser.service_extract_region_main("douyin.search_results", 80)).thenReturn(
                "{\"ok\":true,\"results\":[{\"kind\":\"extract_region\",\"payload\":{\"regionKey\":\"douyin.search_results\",\"items\":[]}}]}");

        assertThatThrownBy(() -> adapter.applySort(new DouyinLeadAcquisitionInput(
                        "openclaw",
                        "most_liked",
                        1,
                        List.of(),
                        "你好",
                        false)))
                .isInstanceOf(DouyinBrowserException.class)
                .hasMessageContaining("SORT_NOT_CONFIRMED");
        verify(browser, never()).extension_browser_navigate(any(), any(), any());
    }

    @Test
    void openVideoPrefersDomExtractedFirstCardAndVerifiesVideoIdentity() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        when(browser.service_observe_main("all")).thenReturn(
                """
                        {"ok":true,"url":"https://www.douyin.com/search/openclaw","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: openclaw @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}"}
                        """,
                """
                        {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=1111111111111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Text[ref=ref_1, frame=0]: 全网都在养的龙虾 @{820,120 360x40}\\nButton[ref=ref_2, frame=0]: 评论 @{1180,320 80x44}"}
                        """);
        when(browser.service_register_region_main(
                "douyin.search_results", 0.0d, 0.0d, 1920.0d, 900.0d, "search-results-dom"))
                .thenReturn("{\"ok\":true}");
        when(browser.service_extract_region_main("douyin.search_results", 80)).thenReturn(
                """
                        {"ok":true,"results":[{"kind":"extract_region","payload":{"regionKey":"douyin.search_results","items":[
                          {"text":"全网都在养的龙虾，它能做什么？#记者实测OpenClaw 55.7万","tag":"a","href":"https://www.douyin.com/video/1111111111111","bbox":{"x":190,"y":170,"width":245,"height":430},"itemType":"douyin_video_result","hrefs":["https://www.douyin.com/video/1111111111111"]},
                          {"text":"第二个 OpenClaw 视频 7.2万","tag":"a","href":"https://www.douyin.com/video/2222222222222","bbox":{"x":462,"y":168,"width":245,"height":430},"itemType":"douyin_video_result","hrefs":["https://www.douyin.com/video/2222222222222"]}
                        ]}}]}
                        """);
        when(browser.service_click_main(312.5d, 385.0d)).thenReturn("{\"ok\":true}");
        when(browser.extension_browser_wait("time", 1800L, null, null, null)).thenReturn("{\"ok\":true}");

        DouyinBrowserAdapter.BrowserObservation opened = adapter.openVideo(0);

        assertThat(opened.code()).isEqualTo("VIDEO_TARGET");
        assertThat(opened.message()).contains("source=dom_extract");
        assertThat(opened.message()).contains("1111111111111");
        verify(browser).service_click_main(312.5d, 385.0d);
    }

    @Test
    void openVideoCanUseGenericDomExtractedVideoLinksWhenExtensionIsNotReloaded() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        when(browser.service_observe_main("all")).thenReturn(
                """
                        {"ok":true,"url":"https://www.douyin.com/search/openclaw","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Searchbox[ref=ref_1, frame=0]: openclaw @{454,9 315x38}\\nText[ref=ref_2, frame=0]: 筛选 @{1810,66 54x26}"}
                        """,
                """
                        {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=1111111111111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":900},"tree":"Text[ref=ref_1, frame=0]: 全网都在养的龙虾 @{820,120 360x40}\\nButton[ref=ref_2, frame=0]: 评论 @{1180,320 80x44}"}
                        """);
        when(browser.service_register_region_main(
                "douyin.search_results", 0.0d, 0.0d, 1920.0d, 900.0d, "search-results-dom"))
                .thenReturn("{\"ok\":true}");
        when(browser.service_extract_region_main("douyin.search_results", 80)).thenReturn(
                """
                        {"ok":true,"results":[{"kind":"extract_region","payload":{"regionKey":"douyin.search_results","items":[
                          {"text":"全网都在养的龙虾，它能做什么？#记者实测OpenClaw 55.7万","tag":"a","href":"https://www.douyin.com/video/1111111111111","bbox":{"x":190,"y":170,"width":245,"height":430},"hrefs":["https://www.douyin.com/video/1111111111111"]},
                          {"text":"第二个 OpenClaw 视频 7.2万","tag":"a","href":"https://www.douyin.com/video/2222222222222","bbox":{"x":462,"y":168,"width":245,"height":430},"hrefs":["https://www.douyin.com/video/2222222222222"]}
                        ]}}]}
                        """);
        when(browser.service_click_main(312.5d, 385.0d)).thenReturn("{\"ok\":true}");
        when(browser.extension_browser_wait("time", 1800L, null, null, null)).thenReturn("{\"ok\":true}");

        DouyinBrowserAdapter.BrowserObservation opened = adapter.openVideo(0);

        assertThat(opened.code()).isEqualTo("VIDEO_TARGET");
        assertThat(opened.message()).contains("source=dom_extract_generic");
        assertThat(opened.message()).contains("1111111111111");
        verify(browser).service_click_main(312.5d, 385.0d);
    }

    @Test
    void openCommentsUsesXShortcutBeforeClickingSideActions() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        when(browser.service_observe_main("all")).thenReturn(
                """
                        {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1280,"h":800},"tree":"Button[ref=ref_1, frame=0]: 点赞 @{1170,240 80x44}\\nStaticText[ref=ref_2, frame=0]: 151 @{1184,316 48x24}\\nButton[ref=ref_3, frame=0]: 收藏 @{1170,392 80x44}"}
                        """,
                """
                        {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1280,"h":800},"tree":"Tab[ref=ref_1, frame=0]: 详情 @{780,88 72x28}\\nTab[ref=ref_2, frame=0]: 评论 @{860,88 72x28}\\nStaticText[ref=ref_3, frame=0]: 回复 @{860,260 80x24}"}
                        """);
        when(browser.service_press_key_main("x")).thenReturn("{\"ok\":true}");

        DouyinBrowserAdapter.BrowserObservation opened = adapter.openComments();

        assertThat(opened.tree()).contains("回复");
        assertThat(opened.code()).isEqualTo("COMMENTS_OPEN");
        assertThat(opened.message()).contains("method=shortcut_x");
        verify(browser).service_click_main(537.6d, 416.0d);
        verify(browser).service_press_key_main("x");
        verify(browser, never()).service_click_text_main("评论", "button", null);
    }

    @Test
    void openCommentsDoesNotPressToggleShortcutTwiceWhenVerificationIsDelayed() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String closedTree = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1280,"h":800},"tree":"Button[ref=ref_1, frame=0]: 点赞 @{1170,240 80x44}\\nStaticText[ref=ref_2, frame=0]: 151 @{1184,316 48x24}\\nButton[ref=ref_3, frame=0]: 收藏 @{1170,392 80x44}"}
                """;
        String openTree = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1280,"h":800},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 151 @{860,88 140x28}\\nTextbox[ref=ref_2, frame=0]: 说点什么 @{850,740 320x44}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(closedTree, closedTree, openTree);
        when(browser.service_press_key_main("x")).thenReturn("{\"ok\":true}");

        DouyinBrowserAdapter.BrowserObservation opened = adapter.openComments();

        assertThat(opened.tree()).contains("全部评论");
        assertThat(opened.code()).isEqualTo("COMMENTS_OPEN");
        assertThat(opened.message()).contains("method=shortcut_x");
        verify(browser).service_click_main(537.6d, 416.0d);
        verify(browser, times(1)).service_press_key_main("x");
    }

    @Test
    void collectAllCommentsScrollsRegisteredCommentRegionInsteadOfVideoPage() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                960d, 80d, 520d, 760d, "test");
        String firstPage = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{980,88 140x28}\\nLink[ref=ref_2, frame=0]: Ly @{980,180 80x24}\\nStaticText[ref=ref_3, frame=0]: 对于99%的人用豆包就行了。 @{1030,212 260x32}"}
                """;
        String lastPage = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{980,88 140x28}\\nLink[ref=ref_2, frame=0]: Ly @{980,180 80x24}\\nStaticText[ref=ref_3, frame=0]: 对于99%的人用豆包就行了。 @{1030,212 260x32}\\nStaticText[ref=ref_4, frame=0]: 没有更多评论 @{1030,720 160x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(firstPage, lastPage, lastPage);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"1"},
                  {"itemType":"douyin_comment","author":"Ly","text":"对于99%的人用豆包就行了。","href":"https://www.douyin.com/user/MS4w","bbox":{"x":1030,"y":212,"width":260,"height":32}}
                ]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{\"ok\":true}");

        var result = adapter.collectAllComments(region);

        assertThat(result.comments()).hasSize(1);
        assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
        verify(browser, atLeastOnce()).service_scroll_region_main(
                eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong());
        verify(browser, never()).service_scroll_main(
                org.mockito.ArgumentMatchers.anyString(),
                anyDouble(),
                anyDouble(),
                anyDouble());
    }

    @Test
    void collectAllCommentsDoesNotAbortWhenPreWheelPanelVerificationFailsButPanelStillExists() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                960d, 80d, 520d, 760d, "dom-comment-items");
        String firstPage = """
                {"ok":true,"url":"https://www.douyin.com/search/yqx?modal_id=6689257066038676740","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 2 @{980,88 140x28}\\nLink[ref=ref_2, frame=0]: A1 @{980,180 80x24}\\nStaticText[ref=ref_3, frame=0]: 第一条评论内容。 @{1030,212 260x32}\\nTextbox[ref=ref_4, frame=0]: 说点什么 @{980,800 280x44}"}
                """;
        String secondPage = """
                {"ok":true,"url":"https://www.douyin.com/search/yqx?modal_id=6689257066038676740","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 2 @{980,88 140x28}\\nLink[ref=ref_5, frame=0]: A2 @{980,180 80x24}\\nStaticText[ref=ref_6, frame=0]: 第二条评论内容。 @{1030,212 260x32}\\nTextbox[ref=ref_7, frame=0]: 说点什么 @{980,800 280x44}"}
                """;
        String endPage = """
                {"ok":true,"url":"https://www.douyin.com/search/yqx?modal_id=6689257066038676740","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 2 @{980,88 140x28}\\nStaticText[ref=ref_8, frame=0]: 暂时没有更多评论 @{1030,720 200x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(firstPage, firstPage, secondPage, endPage, endPage);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn(
                """
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"2"},
                  {"itemType":"douyin_comment","author":"A1","text":"第一条评论内容。","href":"https://www.douyin.com/user/a1","bbox":{"x":1030,"y":212,"width":260,"height":32}}
                ]}}]}
                """,
                """
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"2"},
                  {"itemType":"douyin_comment","author":"A2","text":"第二条评论内容。","href":"https://www.douyin.com/user/a2","bbox":{"x":1030,"y":212,"width":260,"height":32}}
                ]}}]}
                """,
                """
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"2"},
                  {"itemType":"comment_end","text":"暂时没有更多评论","bbox":{"x":1030,"y":720,"width":200,"height":28}}
                ]}}]}
                """,
                """
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"2"},
                  {"itemType":"comment_end","text":"暂时没有更多评论","bbox":{"x":1030,"y":720,"width":200,"height":28}}
                ]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(
                        """
                        {"ok":true,"results":[{"payload":{
                          "moved":false,
                          "mode":"comment_region_wheel",
                          "reason":"comment_panel_not_verified_before_wheel"
                        }}]}
                        """,
                        """
                        {"ok":true,"results":[{"payload":{
                          "moved":true,
                          "mode":"comment_region_wheel",
                          "reason":"comment_window_advanced",
                          "forwardProgress":true
                        }}]}
                        """,
                        """
                        {"ok":true,"results":[{"payload":{
                          "moved":false,
                          "mode":"comment_region_wheel",
                          "reason":"comment_region_wheel_not_moved"
                        }}]}
                        """);

        var result = adapter.collectAllComments(region);

        assertThat(result.comments()).hasSize(2);
        assertThat(result.complete()).isTrue();
        assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
        assertThat(result.stopReason()).isNotEqualTo("COMMENT_PANEL_LOST_DURING_SCROLL");
        assertThat(result.metadata()).containsEntry("staleScrolls", 2);
        assertThat(result.metadata()).containsEntry("lastScrollReason", "comment_region_wheel_not_moved");
    }

    @Test
    void collectAllCommentsTreatsEndMarkerOutsideRegionAsEndInsteadOfPanelLost() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                960d, 80d, 520d, 480d, "test");
        String firstPage = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 758 @{980,88 160x28}\\nLink[ref=ref_2, frame=0]: Ly @{980,180 80x24}\\nStaticText[ref=ref_3, frame=0]: 第一条评论内容。 @{1030,212 260x32}"}
                """;
        String bottomPage = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_4, frame=0]: 暂时没有更多评论 @{1030,720 200x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(firstPage, bottomPage, bottomPage);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn(
                """
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"758"},
                  {"itemType":"douyin_comment","author":"Ly","text":"第一条评论内容。","href":"https://www.douyin.com/user/MS4w","bbox":{"x":1030,"y":212,"width":260,"height":32}}
                ]}}]}
                """,
                """
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_end","text":"暂时没有更多评论","bbox":{"x":1030,"y":720,"width":200,"height":28}}
                ]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("""
                        {"ok":true,"results":[{"payload":{
                          "moved":true,
                          "mode":"comment_region_wheel",
                          "reason":"url_changed_or_panel_lost"
                        }}]}
                        """);

        var result = adapter.collectAllComments(region);

        assertThat(result.complete()).isTrue();
        assertThat(result.stopReason()).isEqualTo("END_OF_LIST_TOP_LEVEL");
        assertThat(result.metadata()).containsEntry("stableEndMarkerWindows", 2);
        assertThat(result.metadata()).containsEntry("collectedCount", 1);
        assertThat(result.metadata()).containsEntry("declaredCountMismatch", true);
        assertThat(result.metadata()).containsEntry("declaredTotalMayIncludeReplies", true);
        assertThat(result.metadata()).containsEntry("partialCollection", false);
    }

    @Test
    void collectAllCommentsKeepsScrollingAfterCollectedCountReachesDeclaredCountUntilEndMarker() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                960d, 80d, 520d, 760d, "test");
        String page = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 10 @{980,88 140x28}\\nLink[ref=ref_2, frame=0]: Ly @{980,180 80x24}\\nStaticText[ref=ref_3, frame=0]: 对于99%的人用豆包就行了。 @{1030,212 260x32}\\nLink[ref=ref_4, frame=0]: A1 @{980,250 80x24}\\nStaticText[ref=ref_5, frame=0]: 第一条评论内容。 @{1030,282 260x32}\\nLink[ref=ref_6, frame=0]: A2 @{980,320 80x24}\\nStaticText[ref=ref_7, frame=0]: 第二条评论内容。 @{1030,352 260x32}\\nLink[ref=ref_8, frame=0]: A3 @{980,390 80x24}\\nStaticText[ref=ref_9, frame=0]: 第三条评论内容。 @{1030,422 260x32}\\nLink[ref=ref_10, frame=0]: A4 @{980,460 80x24}\\nStaticText[ref=ref_11, frame=0]: 第四条评论内容。 @{1030,492 260x32}\\nLink[ref=ref_12, frame=0]: A5 @{980,530 80x24}\\nStaticText[ref=ref_13, frame=0]: 第五条评论内容。 @{1030,562 260x32}\\nLink[ref=ref_14, frame=0]: A6 @{980,600 80x24}\\nStaticText[ref=ref_15, frame=0]: 第六条评论内容。 @{1030,632 260x32}\\nLink[ref=ref_16, frame=0]: A7 @{980,670 80x24}\\nStaticText[ref=ref_17, frame=0]: 第七条评论内容。 @{1030,702 260x32}"}
                """;
        String endPage = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 10 @{980,88 140x28}\\nStaticText[ref=ref_18, frame=0]: 暂时没有更多评论 @{1030,720 200x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(page, page, endPage, endPage);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"10"},
                  {"itemType":"douyin_comment","author":"Ly","text":"对于99%的人用豆包就行了。","href":"https://www.douyin.com/user/MS4w","bbox":{"x":1030,"y":212,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A1","text":"第一条评论内容。","bbox":{"x":1030,"y":282,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A2","text":"第二条评论内容。","bbox":{"x":1030,"y":352,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A3","text":"第三条评论内容。","bbox":{"x":1030,"y":422,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A4","text":"第四条评论内容。","bbox":{"x":1030,"y":492,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A5","text":"第五条评论内容。","bbox":{"x":1030,"y":562,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A6","text":"第六条评论内容。","bbox":{"x":1030,"y":632,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A7","text":"第七条评论内容。","bbox":{"x":1030,"y":702,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A8","text":"第八条评论内容。","bbox":{"x":1030,"y":742,"width":260,"height":32}},
                  {"itemType":"douyin_comment","author":"A9","text":"第九条评论内容。","bbox":{"x":1030,"y":782,"width":260,"height":32}}
                ]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("up"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"moved\":false,\"mode\":\"comment_region_wheel\",\"reason\":\"comment_region_wheel_not_moved\"}}]}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"moved\":true,\"mode\":\"comment_region_wheel\",\"reason\":\"comment_window_advanced\",\"forwardProgress\":true}}]}");

        var result = adapter.collectAllComments(region);

        assertThat(result.complete()).isTrue();
        assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
        assertThat(result.metadata()).containsEntry("targetCoverageReached", true);
        assertThat(result.metadata()).containsEntry("stableEndMarkerWindows", 2);
        assertThat(result.metadata()).containsEntry("replyExpansionEnabled", false);
        verify(browser, atLeastOnce()).service_scroll_region_main(
                eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void collectAllCommentsUsesObservedNetworkCommentsWhenDomExtractionIsEmpty() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                960d, 80d, 520d, 760d, "test");
        String page = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=733","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{980,88 140x28}\\nTextbox[ref=ref_2, frame=0]: 说点什么 @{980,800 280x44}"}
                """;
        String endPage = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=733","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{980,88 140x28}\\nStaticText[ref=ref_3, frame=0]: 暂时没有更多评论 @{1030,720 200x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(page, page, endPage, endPage);
        when(browser.service_douyin_comment_network_main("drain", null, null, null)).thenReturn("""
                {"ok":true,"results":[{"payload":{"pages":[
                  {"url":"https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=733&cursor=0","requestId":"r1","status":200,"base64Encoded":false,"body":"{\\"aweme_id\\":\\"733\\",\\"total\\":1,\\"has_more\\":false,\\"comments\\":[{\\"cid\\":\\"c1\\",\\"text\\":\\"支持\\",\\"user\\":{\\"nickname\\":\\"Ly\\",\\"sec_uid\\":\\"MS4w\\"}}]}"}
                ]}}]}
                """);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn("""
                {"ok":true,"results":[{"payload":{"items":[]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"moved\":true,\"mode\":\"comment_region_wheel\",\"reason\":\"comment_window_advanced\",\"forwardProgress\":true}}]}");

        var result = adapter.collectAllComments(region);

        assertThat(result.comments()).hasSize(1);
        assertThat(result.comments().getFirst().text()).isEqualTo("支持");
        assertThat(result.comments().getFirst().authorName()).isEqualTo("Ly");
        assertThat(result.complete()).isTrue();
        assertThat(result.stopReason()).isEqualTo("NETWORK_HAS_MORE_FALSE");
        assertThat(result.metadata()).containsEntry("networkObservedComments", 1L);
        assertThat(result.metadata()).containsEntry("networkObservedPages", 1);
        assertThat(result.metadata()).containsEntry("networkHasMoreFalseObserved", true);
        assertThat(result.metadata()).containsEntry("primaryCollectionSource", "network_observed");
        verify(browser, never()).service_scroll_region_main(
                eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void collectAllCommentsDoesNotMixDomItemsAfterNetworkTerminalPage() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                960d, 80d, 520d, 760d, "test");
        String page = """
                {"ok":true,"url":"https://www.douyin.com/video/6689257066038676740","title":"易企秀 - 抖音","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{980,88 140x28}\\nTextbox[ref=ref_2, frame=0]: 说点什么 @{980,800 280x44}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(page);
        when(browser.service_douyin_comment_network_main("drain", null, null, null)).thenReturn("""
                {"ok":true,"results":[{"payload":{"pages":[
                  {"url":"https://www-hj.douyin.com/aweme/v1/web/comment/list/?aweme_id=6689257066038676740&cursor=0","requestId":"r1","status":200,"base64Encoded":false,"body":"{\\"aweme_id\\":\\"6689257066038676740\\",\\"total\\":1,\\"has_more\\":false,\\"comments\\":[{\\"cid\\":\\"c1\\",\\"text\\":\\"网络评论\\",\\"user\\":{\\"nickname\\":\\"网络用户\\",\\"sec_uid\\":\\"MS4w\\"}}]}"}
                ]}}]}
                """);
        when(browser.service_extract_region_main(
                eq("douyin.comments"),
                eq(80),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn("""
                {"ok":true,"results":[{"payload":{"items":[{"author":"DOM用户","text":"DOM评论"}]}}]}
                """);

        var result = adapter.collectAllComments(region);

        assertThat(result.comments()).hasSize(1);
        assertThat(result.comments().getFirst().text()).isEqualTo("网络评论");
        assertThat(result.metadata()).containsEntry("networkObservedComments", 1L);
        assertThat(result.metadata()).containsEntry("extractedRegionComments", 0L);
        assertThat(result.metadata()).containsEntry("primaryCollectionSource", "network_observed");
        verify(browser, never()).service_extract_region_main(
                eq("douyin.comments"),
                eq(80),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void collectAllCommentsUsesNetworkDeclaredCountOverUiTextWhenNetworkIsAvailable() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                960d, 80d, 520d, 760d, "test");
        String page = """
                {"ok":true,"url":"https://www.douyin.com/search/yqx?modal_id=6689257066038676740","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 90 @{980,88 140x28}\\nTextbox[ref=ref_2, frame=0]: 说点什么 @{980,800 280x44}"}
                """;
        String endPage = """
                {"ok":true,"url":"https://www.douyin.com/search/yqx?modal_id=6689257066038676740","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 90 @{980,88 140x28}\\nStaticText[ref=ref_3, frame=0]: 暂时没有更多评论 @{1030,720 200x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(page, page, endPage, endPage);
        when(browser.service_douyin_comment_network_main("drain", null, null, null)).thenReturn("""
                {"ok":true,"results":[{"payload":{"pages":[
                  {"url":"https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=6689257066038676740&cursor=0","status":200,"body":"{\\"aweme_id\\":\\"6689257066038676740\\",\\"total\\":144,\\"has_more\\":false,\\"comments\\":[{\\"cid\\":\\"c1\\",\\"text\\":\\"支持\\",\\"user\\":{\\"nickname\\":\\"Ly\\",\\"sec_uid\\":\\"MS4w\\"}}]}"}
                ]}}]}
                """);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn("""
                {"ok":true,"results":[{"payload":{"items":[]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"moved\":true,\"mode\":\"comment_region_wheel\",\"reason\":\"comment_window_advanced\",\"forwardProgress\":true}}]}");

        var result = adapter.collectAllComments(region);

        assertThat(result.declaredCommentCount()).isEqualTo(144);
        assertThat(result.complete()).isTrue();
        assertThat(result.stopReason()).isEqualTo("NETWORK_HAS_MORE_FALSE");
        assertThat(result.metadata()).containsEntry("remainingDeclaredComments", 143);
        assertThat(result.metadata()).containsEntry("declaredCountMismatchReason", "declared_count_may_include_collapsed_replies");
    }

    @Test
    void collectAllCommentsDoesNotUseA11yFallbackWhenNetworkPagesAreUnavailable() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                718d, 17d, 562d, 558d, "a11y-comment-items+safe-point");
        String page = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/ai?modal_id=7562908390894079291","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1280,"h":575},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 2 @{760,58 140x28}\\nLink[ref=ref_2, frame=0]: 欢愉 @{760,124 90x28}\\nStaticText[ref=ref_3, frame=0]: 听了半天就是在卖广告 @{810,164 280x32}\\nLink[ref=ref_4, frame=0]: 见素抱朴 @{760,230 100x28}\\nStaticText[ref=ref_5, frame=0]: 又是转折点，又是财富！你们这些博主天天的服了 @{810,270 380x32}\\nTextbox[ref=ref_6, frame=0]: 说点什么 @{760,520 320x44}"}
                """;
        String endPage = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/ai?modal_id=7562908390894079291","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1280,"h":575},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 2 @{760,58 140x28}\\nStaticText[ref=ref_7, frame=0]: 暂时没有更多评论 @{810,500 200x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(page, page, endPage, endPage);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn("""
                {"ok":true,"results":[{"payload":{"items":[]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"moved\":true,\"mode\":\"comment_region_wheel\",\"reason\":\"comment_window_advanced\",\"forwardProgress\":true}}]}");

        var result = adapter.collectAllComments(region);

        assertThat(result.comments()).isEmpty();
        assertThat(result.complete()).isFalse();
        assertThat(result.stopReason()).isEqualTo("NETWORK_PAGES_NOT_OBSERVED");
        assertThat(result.metadata()).containsEntry("a11yTreeComments", 0L);
        assertThat(result.metadata()).containsEntry("primaryCollectionSource", "a11y_tree");
        assertThat(result.metadata()).containsEntry("networkOnlyCollection", true);
        verify(browser, atLeastOnce()).service_scroll_region_main(
                eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void collectAllCommentsDoesNotMergeA11yCommentsWhenDomExtractionSucceeds() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                718d, 17d, 562d, 558d, "dom-comment-items");
        String page = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/yqx?modal_id=6689257066038676740","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1280,"h":575},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{760,58 140x28}\\nLink[ref=ref_2, frame=0]: 视频作者 @{760,124 90x28}\\nStaticText[ref=ref_3, frame=0]: 工作都很忙，幸好还有你！❤ #易企秀 #vlog日常 @{810,164 360x32}\\nLink[ref=ref_4, frame=0]: 污染作者 @{760,230 100x28}\\nStaticText[ref=ref_5, frame=0]: 这不是结构化 DOM 评论 @{810,270 260x32}\\nTextbox[ref=ref_6, frame=0]: 说点什么 @{760,520 320x44}"}
                """;
        String endPage = """
                {"ok":true,"url":"https://www.douyin.com/jingxuan/search/yqx?modal_id=6689257066038676740","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1280,"h":575},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{760,58 140x28}\\nStaticText[ref=ref_7, frame=0]: 暂时没有更多评论 @{810,500 200x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(page, page, endPage, endPage);
        when(browser.service_douyin_comment_network_main("drain", null, null, null)).thenReturn("""
                {"ok":true,"results":[{"payload":{"pages":[]}}]}
                """);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"1"},
                  {"itemType":"douyin_comment","author":"真实用户","text":"这是结构化 DOM 评论","href":"https://www.douyin.com/user/MS4w","bbox":{"x":780,"y":212,"width":260,"height":32}}
                ]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("{\"ok\":true,\"results\":[{\"payload\":{\"moved\":true,\"mode\":\"comment_region_wheel\",\"reason\":\"comment_window_advanced\",\"forwardProgress\":true}}]}");

        var result = adapter.collectAllComments(region);

        assertThat(result.comments()).hasSize(1);
        assertThat(result.comments().getFirst().authorName()).isEqualTo("真实用户");
        assertThat(result.comments().getFirst().text()).isEqualTo("这是结构化 DOM 评论");
        assertThat(result.metadata()).containsEntry("extractedRegionComments", 1L);
        assertThat(result.metadata()).containsEntry("a11yTreeComments", 0L);
        assertThat(result.metadata()).containsEntry("primaryCollectionSource", "extract_region");
    }

    @Test
    void collectAllCommentsIgnoresNoNewWindowsUntilEndMarker() {
        ExtensionBrowserTool browser = mock(ExtensionBrowserTool.class);
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                browser,
                new ObjectMapper(),
                new DouyinCommentCollector());
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                960d, 80d, 520d, 760d, "test");
        String page = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{980,88 140x28}\\nLink[ref=ref_2, frame=0]: Ly @{980,180 80x24}\\nStaticText[ref=ref_3, frame=0]: 第一条评论内容。 @{1030,212 260x32}"}
                """;
        String endPage = """
                {"ok":true,"url":"https://www.douyin.com/search/openclaw?modal_id=111","title":"发现更多精彩视频 - 抖音搜索","viewport":{"w":1920,"h":855},"tree":"StaticText[ref=ref_1, frame=0]: 全部评论 1 @{980,88 140x28}\\nLink[ref=ref_2, frame=0]: Ly @{980,180 80x24}\\nStaticText[ref=ref_3, frame=0]: 第一条评论内容。 @{1030,212 260x32}\\nStaticText[ref=ref_4, frame=0]: 暂时没有更多评论 @{1030,720 200x28}"}
                """;
        when(browser.service_observe_main("all")).thenReturn(page, page, endPage, endPage);
        when(browser.service_extract_region_main("douyin.comments", 160)).thenReturn("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_count","text":"1"},
                  {"itemType":"douyin_comment","author":"Ly","text":"第一条评论内容。","href":"https://www.douyin.com/user/MS4w","bbox":{"x":1030,"y":212,"width":260,"height":32}}
                ]}}]}
                """);
        when(browser.service_hover_main(anyDouble(), anyDouble())).thenReturn("{\"ok\":true}");
        when(browser.service_scroll_region_main(eq("douyin.comments"), eq("down"), anyDouble(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("""
                        {"ok":true,"results":[{"payload":{
                          "moved":true,
                          "mode":"comment_region_wheel",
                          "reason":"comment_window_advanced",
                          "forwardProgress":true,
                          "newVisibleItemCount":1,
                          "afterWindowSignature":"same-window"
                        }}]}
                        """);

        var result = adapter.collectAllComments(region);

        assertThat(result.complete()).isTrue();
        assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
        assertThat(result.comments()).hasSize(1);
        assertThat(result.metadata()).containsEntry("stableEndMarkerWindows", 2);
        assertThat(result.metadata()).containsEntry("lastScrollForwardProgress", true);
    }

    @Test
    void commentTriggerLocatorPrefersActionButtonOverCommentPanelHeader() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                StaticText[ref=ref_1, frame=0]: 全部评论 151 @{860,88 140x28}
                Button[ref=ref_2, frame=0]: 评论 151 @{1140,352 96x42}
                StaticText[ref=ref_3, frame=0]: 这个正文里也可能出现评论两个字 @{850,260 260x38}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point = adapter.findCommentTriggerPoint(tree, 1280, 800);

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(1188d);
        assertThat(point.y()).isEqualTo(373d);
    }

    @Test
    void commentTriggerLocatorInfersNumericActionBetweenLikeAndCollect() {
        ExtensionDouyinBrowserAdapter adapter = new ExtensionDouyinBrowserAdapter(
                mock(ExtensionBrowserTool.class),
                new ObjectMapper(),
                mock(DouyinCommentCollector.class));
        String tree = """
                Button[ref=ref_1, frame=0]: 点赞 @{1170,240 80x44}
                StaticText[ref=ref_2, frame=0]: 151 @{1184,316 48x24}
                Button[ref=ref_3, frame=0]: 收藏 @{1170,392 80x44}
                Button[ref=ref_4, frame=0]: 分享 @{1170,480 80x44}
                """;

        ExtensionDouyinBrowserAdapter.ClickPoint point = adapter.findCommentTriggerPoint(tree, 1280, 800);

        assertThat(point).isNotNull();
        assertThat(point.x()).isEqualTo(1208d);
        assertThat(point.y()).isEqualTo(328d);
    }
}
