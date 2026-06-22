package vip.mate.lead.douyin.match;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.lead.douyin.model.DouyinCommentItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

@Component
public class CommentMatcher {

    private final CommentAiClassifier aiClassifier;

    public CommentMatcher() {
        this(null);
    }

    @Autowired
    public CommentMatcher(@Nullable CommentAiClassifier aiClassifier) {
        this.aiClassifier = aiClassifier;
    }

    public List<CommentMatchResult> match(List<DouyinCommentItem> comments, List<CommentMatchRule> rules) {
        return match(comments, rules, null);
    }

    public List<CommentMatchResult> match(List<DouyinCommentItem> comments,
                                          List<CommentMatchRule> rules,
                                          BiConsumer<Integer, Integer> progress) {
        List<CommentMatchRule> normalizedRules = CommentMatchRule.normalize(rules);
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        List<CommentMatchResult> results = new ArrayList<>();
        for (DouyinCommentItem comment : comments) {
            results.add(scoreKeywordRules(comment, normalizedRules));
        }
        results = applySemanticRules(results, normalizedRules, progress);
        results.sort(Comparator
                .comparing(CommentMatchResult::matched, Comparator.reverseOrder())
                .thenComparing(CommentMatchResult::score, Comparator.reverseOrder()));
        return results;
    }

    public List<CommentMatchResult> matched(List<DouyinCommentItem> comments, List<CommentMatchRule> rules) {
        return matched(comments, rules, null);
    }

    public List<CommentMatchResult> matched(List<DouyinCommentItem> comments,
                                            List<CommentMatchRule> rules,
                                            BiConsumer<Integer, Integer> progress) {
        return match(comments, rules, progress).stream()
                .filter(CommentMatchResult::matched)
                .toList();
    }

    public CommentMatchResult scoreKeywordRules(DouyinCommentItem comment, List<CommentMatchRule> rules) {
        String candidate = clean(comment == null ? "" : comment.text());
        List<CommentMatchRule> keywordRules = CommentMatchRule.normalize(rules).stream()
                .filter(CommentMatchRule::keyword)
                .toList();
        if (candidate.isBlank() || keywordRules.isEmpty()) {
            return new CommentMatchResult(comment, false, 0d, "no_keyword_match");
        }
        CommentMatchResult best = new CommentMatchResult(comment, false, 0d, "no_keyword_match");
        for (CommentMatchRule rule : keywordRules) {
            CommentMatchResult result = scoreKeywordRule(comment, candidate, rule);
            if (result.score() > best.score() || (result.matched() && !best.matched())) {
                best = result;
            }
        }
        return best;
    }

    private CommentMatchResult scoreKeywordRule(DouyinCommentItem comment, String candidate, CommentMatchRule rule) {
        String target = clean(rule.value());
        if (target.isBlank()) {
            return new CommentMatchResult(comment, false, 0d, "empty_keyword_rule");
        }
        String normalizedCandidate = normalizeExact(candidate);
        String normalizedTarget = normalizeExact(target);
        if (normalizedCandidate.isBlank() || normalizedTarget.isBlank()) {
            return new CommentMatchResult(comment, false, 0d, "empty_keyword_rule");
        }
        if (normalizedCandidate.equals(normalizedTarget)) {
            return new CommentMatchResult(comment, true, 1.0d, "keyword_exact_match:" + target);
        }
        if (normalizedCandidate.contains(normalizedTarget)) {
            return new CommentMatchResult(comment, true, 0.98d, "keyword_contains:" + target);
        }
        return new CommentMatchResult(comment, false, 0d, "keyword_miss:" + target);
    }

    List<CommentMatchResult> applySemanticRules(List<CommentMatchResult> results, List<CommentMatchRule> rules) {
        return applySemanticRules(results, rules, null);
    }

    List<CommentMatchResult> applySemanticRules(List<CommentMatchResult> results,
                                                List<CommentMatchRule> rules,
                                                BiConsumer<Integer, Integer> progress) {
        if (aiClassifier == null || results.isEmpty()) {
            return results;
        }
        List<CommentMatchRule> semanticRules = CommentMatchRule.normalize(rules).stream()
                .filter(CommentMatchRule::semantic)
                .toList();
        if (semanticRules.isEmpty()) {
            return results;
        }
        List<CommentMatchResult> candidates = results.stream()
                .filter(result -> !result.matched())
                .filter(result -> result.comment() != null && !clean(result.comment().text()).isBlank())
                .toList();
        if (candidates.isEmpty()) {
            return results;
        }
        List<CommentMatchResult> semanticResults = aiClassifier.classify(semanticRules, candidates, progress);
        if (semanticResults == null || semanticResults.isEmpty()) {
            return results;
        }
        Map<String, CommentMatchResult> semanticByKey = new LinkedHashMap<>();
        for (CommentMatchResult semanticResult : semanticResults) {
            if (semanticResult != null && semanticResult.comment() != null) {
                semanticByKey.put(semanticResult.comment().commentKey(), semanticResult);
            }
        }
        if (semanticByKey.isEmpty()) {
            return results;
        }
        List<CommentMatchResult> merged = new ArrayList<>(results.size());
        for (CommentMatchResult result : results) {
            CommentMatchResult semanticResult = result.comment() == null
                    ? null
                    : semanticByKey.get(result.comment().commentKey());
            merged.add(semanticResult != null && semanticResult.score() > result.score() ? semanticResult : result);
        }
        return merged;
    }

    private String normalizeExact(String text) {
        return clean(text)
                .toLowerCase(Locale.ROOT)
                .replace("％", "%")
                .replaceAll("[\\s\\p{Punct}，。！？；：“”‘’、]", "");
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
