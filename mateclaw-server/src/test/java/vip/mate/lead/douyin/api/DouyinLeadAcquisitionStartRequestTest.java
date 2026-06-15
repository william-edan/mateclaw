package vip.mate.lead.douyin.api;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.lead.douyin.model.CommentMatchRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DouyinLeadAcquisitionStartRequestTest {

    @Test
    void normalizesOptionalV2Defaults() {
        var input = new DouyinLeadAcquisitionStartRequest(
                "易企秀",
                null,
                null,
                List.of(),
                null,
                null,
                null).normalized();

        assertThat(input.keyword()).isEqualTo("易企秀");
        assertThat(input.sort()).isEqualTo("comprehensive");
        assertThat(input.videoLimit()).isEqualTo(50);
        assertThat(input.matchRules()).isEmpty();
        assertThat(input.dmDraft()).isEqualTo("你好");
        assertThat(input.engage()).isTrue();
        assertThat(input.sendDm()).isFalse();
    }

    @Test
    void defaultsMissingMatchRulesToHighIntentRules() {
        var input = new DouyinLeadAcquisitionStartRequest(
                "AI工具",
                null,
                null,
                null,
                null,
                null,
                null).normalized();

        assertThat(input.matchRules())
                .singleElement()
                .satisfies(rule -> {
                    assertThat(rule.mode()).isEqualTo("semantic");
                    assertThat(rule.value())
                            .contains("高意向客户")
                            .contains("价格", "怎么收费", "能试用吗", "求推荐", "想了解", "怎么联系");
                });
    }

    @Test
    void disablesHighIntentWhenRequested() {
        var input = new DouyinLeadAcquisitionStartRequest(
                "AI工具",
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null).normalized();

        assertThat(input.matchRules()).isEmpty();
    }

    @Test
    void usesCustomHighIntentExamples() {
        var input = new DouyinLeadAcquisitionStartRequest(
                "AI工具",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true,
                List.of("有没有方案", "报价")).normalized();

        assertThat(input.matchRules())
                .singleElement()
                .satisfies(rule -> assertThat(rule.value())
                        .contains("高意向客户")
                        .contains("有没有方案", "报价")
                        .doesNotContain("能试用吗"));
    }

    @Test
    void usesCommentMatchTargetDescriptionBeforeLegacyHighIntent() {
        var input = new DouyinLeadAcquisitionStartRequest(
                "AI工具",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true,
                List.of("多少钱"),
                null,
                "体验不好的用户",
                "匹配抱怨现有工具慢、不好用、出错，或表达想换方案的评论。",
                List.of("太慢了", "不好用")).normalized();

        assertThat(input.matchRules())
                .singleElement()
                .satisfies(rule -> {
                    assertThat(rule.mode()).isEqualTo("semantic");
                    assertThat(rule.value())
                            .contains("评论匹配目标：体验不好的用户")
                            .contains("匹配描述：匹配抱怨现有工具慢")
                            .contains("太慢了", "不好用")
                            .doesNotContain("高意向客户")
                            .doesNotContain("多少钱");
                });
    }

    @Test
    void normalizesStructuredMatchRules() {
        var input = new DouyinLeadAcquisitionStartRequest(
                "易企秀",
                null,
                2,
                List.of(
                        new CommentMatchRule("keyword", "慢出心脏病"),
                        new CommentMatchRule("semantic", "抱怨易企秀加载慢的人")),
                "你好",
                false,
                true).normalized();

        assertThat(input.matchRules())
                .extracting(CommentMatchRule::mode)
                .containsExactly("keyword", "semantic");
    }

    @Test
    void rejectsBlankKeyword() {
        assertThatThrownBy(() -> new DouyinLeadAcquisitionStartRequest(
                " ",
                null,
                null,
                null,
                null,
                null,
                null).normalized())
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("Douyin keyword is required");
    }

    @Test
    void rejectsVideoLimitOutsideAllowedRange() {
        assertThatThrownBy(() -> new DouyinLeadAcquisitionStartRequest(
                "易企秀",
                "most_liked",
                51,
                null,
                null,
                null,
                null).normalized())
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("between 1 and 50");
    }
}
