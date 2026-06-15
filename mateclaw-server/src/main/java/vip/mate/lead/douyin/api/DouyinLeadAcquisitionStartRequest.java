package vip.mate.lead.douyin.api;

import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.exception.MateClawException;

import java.util.ArrayList;
import java.util.List;

public record DouyinLeadAcquisitionStartRequest(
        String keyword,
        String sort,
        Integer videoLimit,
        List<CommentMatchRule> matchRules,
        String dmDraft,
        Boolean sendDm,
        Boolean engage,
        Boolean matchHighIntent,
        List<String> highIntentExamples,
        Boolean startFromCurrentVideo,
        String matchProfile,
        String matchDescription,
        List<String> matchExamples
) {
    public DouyinLeadAcquisitionStartRequest(
            String keyword,
            String sort,
            Integer videoLimit,
            List<CommentMatchRule> matchRules,
            String dmDraft,
            Boolean sendDm
    ) {
        this(keyword, sort, videoLimit, matchRules, dmDraft, sendDm, null, null, null, null, null, null, null);
    }

    public DouyinLeadAcquisitionStartRequest(
            String keyword,
            String sort,
            Integer videoLimit,
            List<CommentMatchRule> matchRules,
            String dmDraft,
            Boolean sendDm,
            Boolean engage
    ) {
        this(keyword, sort, videoLimit, matchRules, dmDraft, sendDm, engage, null, null, null, null, null, null);
    }

    public DouyinLeadAcquisitionStartRequest(
            String keyword,
            String sort,
            Integer videoLimit,
            List<CommentMatchRule> matchRules,
            String dmDraft,
            Boolean sendDm,
            Boolean engage,
            Boolean matchHighIntent
    ) {
        this(keyword, sort, videoLimit, matchRules, dmDraft, sendDm, engage, matchHighIntent, null, null, null, null, null);
    }

    public DouyinLeadAcquisitionStartRequest(
            String keyword,
            String sort,
            Integer videoLimit,
            List<CommentMatchRule> matchRules,
            String dmDraft,
            Boolean sendDm,
            Boolean engage,
            Boolean matchHighIntent,
            List<String> highIntentExamples
    ) {
        this(keyword, sort, videoLimit, matchRules, dmDraft, sendDm, engage, matchHighIntent, highIntentExamples, null, null, null, null);
    }

    public DouyinLeadAcquisitionInput normalized() {
        if (keyword == null || keyword.isBlank()) {
            throw new MateClawException("err.lead.douyin.keyword_required", "Douyin keyword is required");
        }
        if (videoLimit != null
                && (videoLimit < DouyinLeadAcquisitionInput.MIN_VIDEO_LIMIT
                || videoLimit > DouyinLeadAcquisitionInput.MAX_VIDEO_LIMIT)) {
            throw new MateClawException("err.lead.douyin.video_limit_invalid",
                    "Douyin videoLimit must be between 1 and 50");
        }
        return new DouyinLeadAcquisitionInput(
                keyword,
                sort,
                videoLimit == null ? DouyinLeadAcquisitionInput.DEFAULT_VIDEO_LIMIT : videoLimit,
                effectiveMatchRules(),
                dmDraft,
                Boolean.TRUE.equals(sendDm),
                engage == null || Boolean.TRUE.equals(engage),
                Boolean.TRUE.equals(startFromCurrentVideo));
    }

    private List<CommentMatchRule> effectiveMatchRules() {
        List<CommentMatchRule> normalized = CommentMatchRule.normalize(matchRules);
        if (matchDescription != null && !matchDescription.isBlank()) {
            List<CommentMatchRule> targetRules = DouyinLeadAcquisitionInput.matchTargetRules(
                    matchProfile,
                    matchDescription,
                    matchExamples);
            if (normalized.isEmpty()) {
                return targetRules;
            }
            ArrayList<CommentMatchRule> merged = new ArrayList<>(targetRules);
            merged.addAll(normalized);
            return List.copyOf(merged);
        }
        boolean useHighIntent = Boolean.TRUE.equals(matchHighIntent)
                || (matchHighIntent == null && matchRules == null);
        if (!useHighIntent) {
            return normalized;
        }
        List<String> examples = highIntentExamples == null
                ? DouyinLeadAcquisitionInput.DEFAULT_HIGH_INTENT_EXAMPLES
                : highIntentExamples;
        List<CommentMatchRule> highIntentRules = DouyinLeadAcquisitionInput.defaultMatchRules(examples);
        if (normalized.isEmpty()) {
            return highIntentRules;
        }
        ArrayList<CommentMatchRule> merged = new ArrayList<>(highIntentRules);
        merged.addAll(normalized);
        return List.copyOf(merged);
    }
}
