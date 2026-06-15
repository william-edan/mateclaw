package vip.mate.lead.douyin.match;

import org.junit.jupiter.api.Test;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.lead.douyin.model.DouyinCommentItem;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMatcherTest {

    private final CommentMatcher matcher = new CommentMatcher();

    @Test
    void keywordExactMatchHitsOneHundredPercent() {
        DouyinCommentItem ly = comment("Ly", "对于99%的人用豆包就行了。");

        CommentMatchResult result = matcher.scoreKeywordRules(
                ly,
                List.of(CommentMatchRule.keyword("对于99%的人用豆包就行了。")));

        assertThat(result.matched()).isTrue();
        assertThat(result.score()).isEqualTo(1.0d);
        assertThat(result.reason()).startsWith("keyword_exact_match");
    }

    @Test
    void keywordPunctuationDifferenceStillMatches() {
        DouyinCommentItem ly = comment("Ly", "对于99%的人用豆包就行了");

        CommentMatchResult result = matcher.scoreKeywordRules(
                ly,
                List.of(CommentMatchRule.keyword("对于99%的人用豆包就行了。")));

        assertThat(result.matched()).isTrue();
        assertThat(result.score()).isEqualTo(1.0d);
    }

    @Test
    void multipleKeywordRulesMatchAgainstCommentTextOnly() {
        DouyinCommentItem mumaText = comment("不是木马作者", "他叫木马");
        DouyinCommentItem ly = comment("Ly", "对于99%的人用豆包就行了。");
        DouyinCommentItem authorOnly = comment("木马", "这个工具还不错");

        List<CommentMatchResult> results = matcher.matched(
                List.of(authorOnly, mumaText, ly),
                List.of(
                        CommentMatchRule.keyword("他叫木马"),
                        CommentMatchRule.keyword("对于99%的人用豆包就行了。")));

        assertThat(results)
                .extracting(result -> result.comment().text())
                .containsExactly("他叫木马", "对于99%的人用豆包就行了。");
        assertThat(results)
                .extracting(result -> result.comment().authorName())
                .doesNotContain("木马");
    }

    @Test
    void semanticRulesUseAiClassifier() {
        CommentMatcher hybridMatcher = new CommentMatcher((rules, candidates) -> candidates.stream()
                .map(candidate -> {
                    boolean hit = candidate.comment().text().contains("慢出心脏病");
                    return new CommentMatchResult(
                            candidate.comment(),
                            hit,
                            hit ? 0.86d : 0.12d,
                            hit ? "semantic_match:抱怨产品速度慢" : "semantic_reject");
                })
                .toList());
        DouyinCommentItem complaint = comment("霞姐一百岁",
                "不建议大家用易企秀，慢出心脏病了。点个模板半天打不开。");
        DouyinCommentItem neutral = comment("路人", "这个教程讲得很清楚");

        List<CommentMatchResult> results = hybridMatcher.matched(
                List.of(neutral, complaint),
                List.of(CommentMatchRule.semantic("找抱怨易企秀加载慢、体验差的人")));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().comment().authorName()).isEqualTo("霞姐一百岁");
        assertThat(results.getFirst().reason()).startsWith("semantic_match");
    }

    @Test
    void semanticAiReceivesAllUnmatchedCandidatesWithoutBusinessLimit() {
        AtomicInteger candidateCount = new AtomicInteger();
        CommentMatcher hybridMatcher = new CommentMatcher((rules, candidates) -> {
            candidateCount.set(candidates.size());
            return candidates.stream()
                    .map(candidate -> {
                        boolean hit = candidate.comment().text().contains("第249条");
                        return new CommentMatchResult(
                                candidate.comment(),
                                hit,
                                hit ? 0.88d : 0.1d,
                                hit ? "semantic_match:晚段候选命中" : "semantic_reject");
                    })
                    .toList();
        });
        List<DouyinCommentItem> comments = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) {
            comments.add(comment("user-" + i, i == 249 ? "第249条评论想咨询替代方案" : "普通评论 " + i));
        }

        List<CommentMatchResult> results = hybridMatcher.matched(
                comments,
                List.of(CommentMatchRule.semantic("找咨询替代方案的人")));

        assertThat(candidateCount).hasValue(250);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().comment().authorName()).isEqualTo("user-249");
    }

    @Test
    void keywordHitsDoNotNeedAiFallback() {
        AtomicInteger aiCalls = new AtomicInteger();
        CommentMatcher hybridMatcher = new CommentMatcher((rules, candidates) -> {
            aiCalls.incrementAndGet();
            return List.of();
        });
        DouyinCommentItem exact = comment("Ly", "对于99%的人用豆包就行了。");

        List<CommentMatchResult> results = hybridMatcher.matched(
                List.of(exact),
                List.of(
                        CommentMatchRule.keyword("对于99%的人用豆包就行了。"),
                        CommentMatchRule.semantic("找提到豆包够用的人")));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().reason()).startsWith("keyword_exact_match");
        assertThat(aiCalls).hasValue(0);
    }

    private DouyinCommentItem comment(String author, String text) {
        return new DouyinCommentItem(
                "video-1",
                "comment-" + author,
                null,
                author,
                "https://www.douyin.com/user/" + author,
                null,
                text,
                null,
                null,
                null,
                null);
    }
}
