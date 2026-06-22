package vip.mate.lead.douyin.match;

import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.CommentMatchRule;

import java.util.List;
import java.util.function.BiConsumer;

@FunctionalInterface
public interface CommentAiClassifier {

    List<CommentMatchResult> classify(List<CommentMatchRule> rules, List<CommentMatchResult> candidates);

    /**
     * 带进度回调的分类。onProgress.accept(已处理候选数, 候选总数) 会在分类推进时被调用，
     * 用于把语义匹配阶段的进度推到 UI（消除"匹配时用户以为卡住"）。
     * onProgress 可能被多线程并发调用，回调实现需自行保证线程安全。
     * 默认实现委托无进度版本，保持向后兼容。
     */
    default List<CommentMatchResult> classify(List<CommentMatchRule> rules,
                                              List<CommentMatchResult> candidates,
                                              BiConsumer<Integer, Integer> onProgress) {
        return classify(rules, candidates);
    }
}
