package vip.mate.lead.douyin.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.lead.douyin.browser.DouyinBrowserAdapter;
import vip.mate.lead.douyin.model.DouyinCommentItem;

import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DouyinCommentCollectorTest {

    private final DouyinCommentCollector collector = new DouyinCommentCollector();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsAuthorAndShortCommentWhenCenterIsInsideRegion() {
        DouyinBrowserAdapter.BrowserObservation obs = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw?modal_id=1",
                "发现更多精彩视频 - 抖音搜索",
                """
                Link[ref=ref_1, frame=0]: Ly @{505,230 32x20}
                Text[ref=ref_2, frame=0]: 对于99%的人用豆包就行了。 @{505,255 190x20}
                """,
                1280,
                720,
                "",
                "");
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                520, 180, 420, 520, "test");

        List<DouyinCommentItem> comments = collector.visibleComments(obs, region);

        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().authorName()).isEqualTo("Ly");
        assertThat(comments.getFirst().text()).isEqualTo("对于99%的人用豆包就行了。");
    }

    @Test
    void detectsDeclaredCommentCount() {
        assertThat(collector.declaredCommentCount("评论 151 条")).isEqualTo(151);
        assertThat(collector.declaredCommentCount("266条评论")).isEqualTo(266);
        assertThat(collector.declaredCommentCount("全部评论(758)")).isEqualTo(758);
        assertThat(collector.declaredCommentCount("全部评论 7,166")).isEqualTo(7166);
        assertThat(collector.declaredCommentCount("7,166条评论")).isEqualTo(7166);
    }

    @Test
    void doesNotInferDeclaredCommentCountFromVideoActionRailWhenHeaderIsMissing() {
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                760, 0, 520, 720, "test");
        String tree = """
                StaticText[ref=ref_1, frame=0]: 11.3万 @{205,360 80x28}
                StaticText[ref=ref_2, frame=0]: 16 @{226,486 36x28}
                StaticText[ref=ref_3, frame=0]: 7637 @{210,610 60x28}
                StaticText[ref=ref_4, frame=0]: 1995 @{210,724 60x28}
                Link[ref=ref_5, frame=0]: 作者 @{780,160 80x24}
                StaticText[ref=ref_6, frame=0]: 评论内容 @{830,196 180x28}
                """;

        assertThat(collector.declaredCommentCount(tree, region)).isZero();
    }

    @Test
    void visibleCommentsIgnoreA11yMetadataLinesAndGenericUserIds() {
        DouyinBrowserAdapter.BrowserObservation obs = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/ai?modal_id=1",
                "发现更多精彩视频 - 抖音搜索",
                """
                Link[ref=ref_1, frame=0]: 哇塞 @{760,124 90x28}
                StaticText[ref=ref_2, frame=0]: 不需要超级个体，只希望公平正义 @{810,164 280x32}
                StaticText[ref=ref_3, frame=0]: 7月前·四川 @{810,200 120x20}
                StaticText[ref=ref_4, frame=0]: 用户4868154654558 @{810,230 160x20}
                Link[ref=ref_5, frame=0]: 实在想不出好名字 @{760,260 160x28}
                StaticText[ref=ref_6, frame=0]: [抠鼻] @{810,292 80x20}
                StaticText[ref=ref_7, frame=0]: 又赢麻了？ @{810,322 160x28}
                """,
                1280,
                720,
                "",
                "");
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                720, 100, 500, 520, "test");

        List<DouyinCommentItem> comments = collector.visibleComments(obs, region);

        assertThat(comments).extracting(DouyinCommentItem::text)
                .containsExactly("不需要超级个体，只希望公平正义", "又赢麻了？");
        assertThat(comments).extracting(DouyinCommentItem::authorName)
                .containsExactly("哇塞", "实在想不出好名字");
    }

    @Test
    void visibleCommentsIgnoreA11yForwardOnlyFragments() {
        DouyinBrowserAdapter.BrowserObservation obs = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/yqx?modal_id=1",
                "发现更多精彩视频 - 抖音搜索",
                """
                Link[ref=ref_1, frame=0]: 易企秀 作者 @{760,124 120x28}
                StaticText[ref=ref_2, frame=0]: 转发 · @{810,164 80x24}
                StaticText[ref=ref_3, frame=0]: 快闪H5制作，如此简单！ @{810,200 260x32}
                """,
                1280,
                720,
                "",
                "");
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                720, 100, 500, 520, "test");

        List<DouyinCommentItem> comments = collector.visibleComments(obs, region);

        assertThat(comments).extracting(DouyinCommentItem::text)
                .containsExactly("快闪H5制作，如此简单！");
    }

    @Test
    void detectsCommentRegionFromCommentItemsInsteadOfRightActionBar() {
        DouyinBrowserAdapter.BrowserObservation obs = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw?modal_id=1",
                "发现更多精彩视频 - 抖音搜索",
                """
                Button[ref=ref_1, frame=0]: 点赞 @{1170,180 80x44}
                StaticText[ref=ref_2, frame=0]: 151 @{1184,260 48x24}
                Button[ref=ref_3, frame=0]: 收藏 @{1170,330 80x44}
                Link[ref=ref_4, frame=0]: 川流不息 @{780,150 90x22}
                StaticText[ref=ref_5, frame=0]: 不懂就问，龙虾是什么意思？ @{830,184 220x24}
                Link[ref=ref_6, frame=0]: Ly @{780,260 32x22}
                StaticText[ref=ref_7, frame=0]: 对于99%的人用豆包就行了。 @{830,294 220x24}
                Textbox[ref=ref_8, frame=0]: 说点什么 @{780,520 260x44}
                """,
                1280,
                575,
                "",
                "");

        DouyinBrowserAdapter.RegionInfo region = collector.detectCommentRegion(obs).orElseThrow();

        assertThat(region.source()).isEqualTo("a11y-comment-items");
        assertThat(region.x()).isLessThan(860d);
        assertThat(region.x() + region.width()).isGreaterThan(1050d);
    }

    @Test
    void detectsEndMarker() {
        assertThat(collector.commentsReachedEnd("暂时没有更多评论")).isTrue();
    }

    @Test
    void detectsEndMarkerOnlyInsideCommentRegionWhenRegionProvided() {
        DouyinBrowserAdapter.RegionInfo region = DouyinBrowserAdapter.RegionInfo.comments(
                900, 0, 380, 700, "test");
        String outsideOnly = "StaticText[ref=ref_1, frame=0]: 没有更多评论 @{100,640 120x20}";
        String inside = "StaticText[ref=ref_1, frame=0]: 没有更多评论 @{980,640 120x20}";

        assertThat(collector.commentsReachedEnd(outsideOnly, region)).isFalse();
        assertThat(collector.commentsReachedEnd(inside, region)).isTrue();
    }

    @Test
    void extractedRegionIgnoresGenericTextItems() throws Exception {
        var root = mapper.readTree("""
                {
                  "ok": true,
                  "results": [{
                    "payload": {
                      "items": [
                        {
                          "text": "Stop Agent",
                          "tag": "button",
                          "bbox": {"x": 10, "y": 10, "width": 80, "height": 24}
                        },
                        {
                          "itemType": "douyin_comment",
                          "author": "Ly",
                          "text": "对于99%的人用豆包就行了。",
                          "href": "https://www.douyin.com/user/MS4w",
                          "bbox": {"x": 10, "y": 40, "width": 280, "height": 48}
                        }
                      ]
                    }
                  }]
                }
                """);

        List<DouyinCommentItem> comments = collector.commentsFromExtractedRegion(root, "https://www.douyin.com/search/openclaw?modal_id=1");

        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().authorName()).isEqualTo("Ly");
        assertThat(comments.getFirst().text()).isEqualTo("对于99%的人用豆包就行了。");
    }

    @Test
    void extractedRegionKeepsShortStructuredComments() throws Exception {
        var root = mapper.readTree("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"douyin_comment","author":"A1","text":"支持","bbox":{"x":10,"y":40,"width":120,"height":28}},
                  {"itemType":"douyin_comment","author":"A2","text":"[捂脸]","bbox":{"x":10,"y":80,"width":120,"height":28}},
                  {"itemType":"douyin_comment","author":"A3","text":"回复","bbox":{"x":10,"y":120,"width":120,"height":28}}
                ]}}]}
                """);

        List<DouyinCommentItem> comments = collector.commentsFromExtractedRegion(root, "video");

        assertThat(comments).extracting(DouyinCommentItem::text)
                .containsExactly("支持", "[捂脸]");
    }

    @Test
    void extractedRegionStripsForwardPrefixAndKeepsVisibilityMetadata() throws Exception {
        var root = mapper.readTree("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"douyin_comment","author":"易企秀","text":"转发 · 快闪H5制作，如此简单！","visibleInRegion":false,"bbox":{"x":10,"y":40,"width":220,"height":28}}
                ]}}]}
                """);

        List<DouyinCommentItem> comments = collector.commentsFromExtractedRegion(root, "video");

        assertThat(comments).hasSize(1);
        assertThat(comments.getFirst().text()).isEqualTo("快闪H5制作，如此简单！");
        assertThat(comments.getFirst().metadata()).containsEntry("visibleInRegion", false);
    }

    @Test
    void extractedRegionDetectsCommentEndMarker() throws Exception {
        var root = mapper.readTree("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"comment_end","text":"暂时没有更多评论","bbox":{"x":410,"y":780,"width":170,"height":28}}
                ]}}]}
                """);

        assertThat(collector.commentsReachedEndFromExtractedRegion(root)).isTrue();
    }

    @Test
    void extractedCommentKeyDoesNotDependOnWindowIndex() throws Exception {
        var first = mapper.readTree("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"douyin_comment","author":"Ly","text":"对于99%的人用豆包就行了。","bbox":{"x":10,"y":40,"width":280,"height":48}}
                ]}}]}
                """);
        var second = mapper.readTree("""
                {"ok":true,"results":[{"payload":{"items":[
                  {"itemType":"douyin_comment","author":"Other","text":"无关评论内容足够长。","bbox":{"x":10,"y":10,"width":260,"height":48}},
                  {"itemType":"douyin_comment","author":"Ly","text":"对于99%的人用豆包就行了。","bbox":{"x":10,"y":80,"width":280,"height":48}}
                ]}}]}
                """);

        DouyinCommentItem firstComment = collector.commentsFromExtractedRegion(first, "https://www.douyin.com/search/openclaw?modal_id=1").getFirst();
        DouyinCommentItem secondComment = collector.commentsFromExtractedRegion(second, "https://www.douyin.com/search/openclaw?modal_id=1").get(1);

        assertThat(firstComment.commentKey()).isEqualTo(secondComment.commentKey());
    }

    @Test
    void parsesObservedNetworkCommentPage() throws Exception {
        var page = mapper.readTree("""
                {
                  "url": "https://www.douyin.com/aweme/v1/web/comment/list/?aweme_id=733&cursor=0",
                  "status": 200,
                  "body": "{\\"aweme_id\\":\\"733\\",\\"cursor\\":\\"0\\",\\"next_cursor\\":\\"20\\",\\"has_more\\":true,\\"total\\":129,\\"comments\\":[{\\"cid\\":\\"c1\\",\\"text\\":\\"支持\\",\\"digg_count\\":12,\\"reply_comment_total\\":3,\\"user\\":{\\"nickname\\":\\"Ly\\",\\"sec_uid\\":\\"MS4w\\",\\"avatar_thumb\\":{\\"url_list\\":[\\"https://p.example/avatar.jpeg\\"]}}}]}"
                }
                """);

        var parsed = collector.commentsFromNetworkPage(page, "fallback-video");

        assertThat(parsed.comments()).hasSize(1);
        assertThat(parsed.declaredCommentCount()).isEqualTo(129);
        assertThat(parsed.cursor()).isEqualTo("0");
        assertThat(parsed.nextCursor()).isEqualTo("20");
        assertThat(parsed.hasMoreKnown()).isTrue();
        assertThat(parsed.hasMore()).isTrue();
        DouyinCommentItem comment = parsed.comments().getFirst();
        assertThat(comment.videoKey()).isEqualTo("733");
        assertThat(comment.authorName()).isEqualTo("Ly");
        assertThat(comment.authorProfileUrl()).isEqualTo("https://www.douyin.com/user/MS4w");
        assertThat(comment.authorAvatarUrl()).isEqualTo("https://p.example/avatar.jpeg");
        assertThat(comment.text()).isEqualTo("支持");
        assertThat(comment.likeCount()).isEqualTo(12);
        assertThat(comment.replyCount()).isEqualTo(3);
        assertThat(comment.metadata()).containsEntry("source", "network_observed");
    }

    @Test
    void parsesNumericHasMoreZeroAsNetworkTerminalPage() throws Exception {
        var page = mapper.readTree("""
                {
                  "url": "https://www-hj.douyin.com/aweme/v1/web/comment/list/?aweme_id=7562908390894079291&cursor=630",
                  "status": 200,
                  "body": "{\\"status_code\\":0,\\"cursor\\":630,\\"has_more\\":0,\\"total\\":760,\\"comments\\":[{\\"cid\\":\\"7562919027288769322\\",\\"text\\":\\"最后一页真实评论\\",\\"aweme_id\\":\\"7562908390894079291\\",\\"digg_count\\":0,\\"user\\":{\\"nickname\\":\\"#一路向北\\",\\"sec_uid\\":\\"MS4wLjABAAAAVHssIob0gCq_Zy65sendXP00tc7fqcq7gmX1YkT03yuMEqzkb0AHyjbaLVzy6ZBM\\"}}]}"
                }
                """);

        var parsed = collector.commentsFromNetworkPage(page, "fallback-video");

        assertThat(parsed.comments()).hasSize(1);
        assertThat(parsed.declaredCommentCount()).isEqualTo(760);
        assertThat(parsed.cursor()).isEqualTo("630");
        assertThat(parsed.nextCursor()).isEqualTo("630");
        assertThat(parsed.hasMoreKnown()).isTrue();
        assertThat(parsed.hasMore()).isFalse();
        assertThat(parsed.comments().getFirst().videoKey()).isEqualTo("7562908390894079291");
        assertThat(parsed.comments().getFirst().authorName()).isEqualTo("#一路向北");
    }

    @Test
    void parsesBase64ObservedNetworkBody() throws Exception {
        String body = """
                {"data":{"hasMore":false,"comments":[
                  {"commentId":"c2","content":"哈哈","authorInfo":{"nickName":"A1"}}
                ]}}
                """;
        var page = mapper.createObjectNode()
                .put("url", "https://www.douyin.com/comment/list")
                .put("base64Encoded", true)
                .put("body", Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));

        var parsed = collector.commentsFromNetworkPage(page, "https://www.douyin.com/search/openclaw?modal_id=733");

        assertThat(parsed.comments()).hasSize(1);
        assertThat(parsed.comments().getFirst().authorName()).isEqualTo("A1");
        assertThat(parsed.comments().getFirst().text()).isEqualTo("哈哈");
        assertThat(parsed.hasMoreKnown()).isTrue();
        assertThat(parsed.hasMore()).isFalse();
    }

    @Test
    void commentRegionIgnoresVideoActionBarAndStartsInRightPanel() {
        DouyinBrowserAdapter.BrowserObservation obs = new DouyinBrowserAdapter.BrowserObservation(
                true,
                "https://www.douyin.com/search/openclaw?modal_id=1",
                "发现更多精彩视频 - 抖音搜索",
                """
                Button[ref=ref_1, frame=0]: 评论 151 @{640,352 96x42}
                Tab[ref=ref_2, frame=0]: 详情 @{820,88 72x28}
                Tab[ref=ref_3, frame=0]: 评论 @{900,88 72x28}
                Tab[ref=ref_4, frame=0]: TA的作品 @{980,88 96x28}
                Link[ref=ref_5, frame=0]: Ly @{850,230 32x20}
                Text[ref=ref_6, frame=0]: 对于99%的人用豆包就行了。 @{850,255 220x20}
                """,
                1280,
                575,
                "",
                "");

        DouyinBrowserAdapter.RegionInfo region = collector.detectCommentRegion(obs).orElseThrow();

        assertThat(region.x()).isGreaterThanOrEqualTo(576d);
        assertThat(region.safeX()).isGreaterThan(760d);
    }
}
