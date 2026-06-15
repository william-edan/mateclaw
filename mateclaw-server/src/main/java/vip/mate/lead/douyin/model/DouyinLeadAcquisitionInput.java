package vip.mate.lead.douyin.model;

import java.util.List;

public record DouyinLeadAcquisitionInput(
        String keyword,
        String sort,
        int videoLimit,
        List<CommentMatchRule> matchRules,
        String dmDraft,
        boolean sendDm,
        boolean engage,
        boolean startFromCurrentVideo
) {
    public static final String DEFAULT_KEYWORD = "openclaw";
    public static final String DEFAULT_SORT = "comprehensive";
    public static final int DEFAULT_VIDEO_LIMIT = 50;
    public static final int MIN_VIDEO_LIMIT = 1;
    public static final int MAX_VIDEO_LIMIT = 50;
    public static final String DEFAULT_DM_DRAFT = "你好";
    public static final String DEFAULT_MATCH_PROFILE = "有意向的客户";
    public static final String DEFAULT_HIGH_INTENT_SEMANTIC_RULE = "高意向客户：评论者明确表达正在寻找工具、方案、服务商、教程、报价、试用、购买渠道、联系方式，或表达当前业务痛点并希望有人提供解决办法。不要命中单纯点赞、收藏、路过、泛泛学习、开玩笑、无明确需求的评论。";
    public static final List<String> DEFAULT_HIGH_INTENT_EXAMPLES = List.of(
            "价格",
            "怎么收费",
            "能试用吗",
            "求推荐",
            "想了解",
            "怎么联系");

    public DouyinLeadAcquisitionInput(
            String keyword,
            String sort,
            int videoLimit,
            List<CommentMatchRule> matchRules,
            String dmDraft,
            boolean sendDm
    ) {
        this(keyword, sort, videoLimit, matchRules, dmDraft, sendDm, true, false);
    }

    public DouyinLeadAcquisitionInput(
            String keyword,
            String sort,
            int videoLimit,
            List<CommentMatchRule> matchRules,
            String dmDraft,
            boolean sendDm,
            boolean engage
    ) {
        this(keyword, sort, videoLimit, matchRules, dmDraft, sendDm, engage, false);
    }

    public DouyinLeadAcquisitionInput(
            String keyword,
            String sort,
            int videoLimit,
            String dmDraft,
            boolean sendDm,
            boolean engage
    ) {
        this(keyword, sort, videoLimit, List.of(), dmDraft, sendDm, engage, false);
    }

    public DouyinLeadAcquisitionInput {
        keyword = defaulted(keyword, DEFAULT_KEYWORD);
        sort = defaulted(sort, DEFAULT_SORT);
        videoLimit = videoLimit <= 0 ? DEFAULT_VIDEO_LIMIT : Math.min(Math.max(videoLimit, MIN_VIDEO_LIMIT), MAX_VIDEO_LIMIT);
        matchRules = matchRules == null ? defaultMatchRules() : CommentMatchRule.normalize(matchRules);
        dmDraft = defaulted(dmDraft, DEFAULT_DM_DRAFT);
    }

    public static DouyinLeadAcquisitionInput defaults() {
        return new DouyinLeadAcquisitionInput(
                DEFAULT_KEYWORD,
                DEFAULT_SORT,
                DEFAULT_VIDEO_LIMIT,
                defaultMatchRules(),
                DEFAULT_DM_DRAFT,
                false,
                true,
                false);
    }

    public static List<CommentMatchRule> defaultMatchRules() {
        return defaultMatchRules(DEFAULT_HIGH_INTENT_EXAMPLES);
    }

    public static List<CommentMatchRule> defaultMatchRules(List<String> examples) {
        return List.of(CommentMatchRule.semantic(defaultHighIntentSemanticRule(examples)));
    }

    public static List<CommentMatchRule> matchTargetRules(String profile, String description, List<String> examples) {
        String rule = matchTargetSemanticRule(profile, description, examples);
        return rule.isBlank() ? List.of() : List.of(CommentMatchRule.semantic(rule));
    }

    public static String matchTargetSemanticRule(String profile, String description, List<String> examples) {
        String normalizedDescription = description == null ? "" : description.trim();
        if (normalizedDescription.isBlank()) {
            return "";
        }
        String normalizedProfile = profile == null || profile.isBlank() ? DEFAULT_MATCH_PROFILE : profile.trim();
        String exampleText = normalizeExamples(examples);
        String base = "评论匹配目标：" + normalizedProfile + "。"
                + "匹配描述：" + normalizedDescription + "。"
                + "只根据评论正文判断，不要使用作者名称；不要命中与描述无关的泛泛点赞、收藏、路过、开玩笑或无明确需求的评论。";
        if (exampleText.isBlank()) {
            return base;
        }
        return base + " 可参考这些示例词或相近说法：" + exampleText + "。";
    }

    public static String defaultHighIntentSemanticRule(List<String> examples) {
        String exampleText = normalizeExamples(examples);
        if (exampleText.isBlank()) {
            return DEFAULT_HIGH_INTENT_SEMANTIC_RULE;
        }
        return DEFAULT_HIGH_INTENT_SEMANTIC_RULE + " 可参考这些高意向表达或相近说法：" + exampleText + "。";
    }

    private static String normalizeExamples(List<String> examples) {
        if (examples == null || examples.isEmpty()) {
            return "";
        }
        return examples.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(30)
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
    }

    public boolean hasMatchRules() {
        return !matchRules.isEmpty();
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
