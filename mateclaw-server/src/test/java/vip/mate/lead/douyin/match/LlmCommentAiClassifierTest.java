package vip.mate.lead.douyin.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.DouyinCommentItem;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCommentAiClassifierTest {

    private final LlmCommentAiClassifier classifier = new LlmCommentAiClassifier(null, null, new ObjectMapper());

    @Test
    void planBatchesKeepsBatchesWithinTokenSafeSize() {
        List<CommentMatchResult> candidates = new ArrayList<>();
        for (int i = 0; i < 170; i++) {
            candidates.add(candidate(i, "想了解替代方案 " + i));
        }

        List<List<CommentMatchResult>> batches = classifier.planBatches(candidates);

        // 批次大小必须 <= MAX_BATCH_SIZE(35):单批输出 JSON 不能超模型 max_tokens,否则被截断成
        // 不完整 JSON → 整批失败二分重试。同时所有候选都要进某批次(不丢评论)。
        // 用不变量断言而非硬编码批次数,这样后续调整批次常量不会再误伤本测试。
        assertThat(batches).isNotEmpty();
        assertThat(batches)
                .extracting(List::size)
                .allSatisfy(size -> assertThat(size).isBetween(1, 35));
        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(170);
    }

    @Test
    void planBatchesSplitsLongCommentsByCharacterBudget() {
        String longText = "加载太慢，想换一个更稳定的工具。".repeat(25);
        List<CommentMatchResult> candidates = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            candidates.add(candidate(i, longText));
        }

        List<List<CommentMatchResult>> batches = classifier.planBatches(candidates);

        assertThat(batches.size()).isGreaterThan(2);
        assertThat(batches)
                .extracting(List::size)
                .allSatisfy(size -> assertThat(size).isLessThan(80));
    }

    @Test
    void truncateCommentTextCapsPromptPayloadPerComment() {
        String longText = "很长的评论".repeat(100);

        String truncated = classifier.truncateCommentText(longText);

        assertThat(truncated).hasSize(300);
        assertThat(classifier.truncateCommentText("  短评论  ")).isEqualTo("短评论");
    }

    private CommentMatchResult candidate(int index, String text) {
        DouyinCommentItem comment = new DouyinCommentItem(
                "video-1",
                "comment-" + index,
                null,
                "作者" + index,
                "https://www.douyin.com/user/" + index,
                null,
                text,
                null,
                null,
                null,
                null);
        return new CommentMatchResult(comment, false, 0d, "keyword_miss");
    }
}
