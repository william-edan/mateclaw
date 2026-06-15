package vip.mate.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionQueryService;
import vip.mate.lead.douyin.api.DouyinLeadAcquisitionRunResponse;
import vip.mate.lead.douyin.api.RunTimelineEventDTO;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DouyinLeadAcquisitionTool {

    private final DouyinLeadAcquisitionRunService runService;
    private final DouyinLeadAcquisitionQueryService queryService;
    private final ObjectMapper objectMapper;

    @Tool(name = "douyin_lead_acquisition_run", description = """
            Start the dedicated Douyin lead-acquisition Skill V2 workflow.
            Use this tool for productized Douyin lead-acquisition tasks such as:
            search a required keyword, sort by comprehensive ranking by default, process up to 50 videos,
            collect loadable comments, match comment text by keyword and/or semantic rules, then optionally open matched
            comment authors, follow, open DM, and type a draft. This tool executes synchronously and
            returns the real terminal result (SUCCEEDED/FAILED/ABORTED) when it finishes.
            The Douyin adapter owns platform-specific browser tactics, including using
            the video home search input and pressing Enter to submit search when the
            visible search button routes to AI search.

            Do not manually perform this workflow with extension_browser_click/type/scroll.
            For comment collection, never claim "full collection" unless the returned
            lead.comments.collected event has complete=true. If complete=false, report it
            as partial collection and include declaredCommentCount, commentsCollected,
            collectionCoverage, and stopReason.
            Never infer anti-bot limits, login limits, platform-side constraints, deletion,
            or hidden comments from NO_NEW_ITEMS_* stop reasons unless the returned
            reportingGuidance explicitly says that evidence was observed. Without explicit
            evidence, say "automatic collection did not complete and did not confirm the
            bottom of the list".
            The workflow matches COMMENT TEXT ONLY. Author names are never used as match
            criteria; after comment text matches, the workflow uses that comment item's
            bound author target for follow and DM draft actions.
            """)
    public String douyinLeadAcquisitionRun(
            @ToolParam(description = "Douyin search keyword. Required.", required = true)
            String keyword,
            @ToolParam(description = "Sort mode. Use comprehensive for 综合排序, most_liked for 最多点赞, latest for 最新发布. Default: comprehensive", required = false)
            String sort,
            @ToolParam(description = "Number of videos to process. Default 50, allowed range 1..50.", required = false)
            Integer videoLimit,
            @ToolParam(description = "Optional keyword rules. Multiple phrases can be separated by newline, semicolon, comma, or slash. Keyword rules use deterministic comment-text contains matching.", required = false)
            String keywordMatchRules,
            @ToolParam(description = "Optional semantic rules. Multiple natural-language lead intents can be separated by newline or semicolon. Semantic rules use the configured LLM.", required = false)
            String semanticMatchRules,
            @ToolParam(description = "Optional label for the comment match target, for example 有意向的客户, 体验不好的用户, 求教程/功能咨询, 竞品替代需求, or 自定义.", required = false)
            String matchProfile,
            @ToolParam(description = "Describe which comment texts should be matched. When supplied, the workflow creates one semantic rule from this description and optional examples.", required = false)
            String matchDescription,
            @ToolParam(description = "Optional example phrases for the match target. Multiple phrases can be separated by newline, semicolon, comma, or slash.", required = false)
            String matchExamples,
            @ToolParam(description = "Whether to use the default high-intent lead classifier. Default true when no explicit rules are supplied.", required = false)
            Boolean matchHighIntent,
            @ToolParam(description = "Optional high-intent example phrases used as model guidance. Multiple phrases can be separated by newline, semicolon, comma, or slash.", required = false)
            String highIntentExamples,
            @ToolParam(description = "DM draft to type after opening the matched author's DM. Default: 你好", required = false)
            String dmDraft,
            @ToolParam(description = "Whether to send the DM. Default false; false means type draft only.", required = false)
            Boolean sendDm,
            @ToolParam(description = "Whether to follow/open DM/draft after matching comments. Default true. Set false for collection-only V1 debugging.", required = false)
            Boolean engage,
            @Nullable ToolContext ctx) {

        ChatOrigin origin = ChatOrigin.from(ctx);
        Long workspaceId = origin.workspaceId() == null ? 1L : origin.workspaceId();
        Long createdBy = parseLongOrDefault(origin.requesterId(), 1L);
        if (keyword == null || keyword.isBlank()) {
            return json(Map.of(
                    "ok", false,
                    "terminal", true,
                    "status", "INPUT_INVALID",
                    "message", "Douyin keyword is required.",
                    "code", "err.lead.douyin.keyword_required"));
        }
        DouyinLeadAcquisitionInput input = new DouyinLeadAcquisitionInput(
                keyword,
                sort,
                videoLimit == null ? DouyinLeadAcquisitionInput.DEFAULT_VIDEO_LIMIT : videoLimit,
                buildMatchRules(keywordMatchRules, semanticMatchRules, matchProfile, matchDescription, matchExamples, matchHighIntent, highIntentExamples),
                dmDraft,
                Boolean.TRUE.equals(sendDm),
                engage == null || Boolean.TRUE.equals(engage));
        DouyinLeadAcquisitionRunResponse result = runService.runSync(workspaceId, createdBy, input, queryService);
        boolean terminal = isTerminal(result.status());
        boolean succeeded = "succeeded".equalsIgnoreCase(result.status());
        return json(Map.of(
                "ok", succeeded,
                "terminal", terminal,
                "status", normalizeStatus(result.status()),
                "message", messageFor(result, terminal),
                "runId", result.runId(),
                "taskId", result.taskId(),
                "input", inputMap(input),
                "reportingGuidance", reportingGuidance(result),
                "result", result));
    }

    public String douyinLeadAcquisitionRun(String keyword,
                                           String sort,
                                           Integer videoLimit,
                                           String keywordMatchRules,
                                           String semanticMatchRules,
                                           String dmDraft,
                                           Boolean sendDm,
                                           Boolean engage,
                                           @Nullable ToolContext ctx) {
        return douyinLeadAcquisitionRun(
                keyword,
                sort,
                videoLimit,
                keywordMatchRules,
                semanticMatchRules,
                null,
                null,
                null,
                null,
                null,
                dmDraft,
                sendDm,
                engage,
                ctx);
    }

    private Map<String, Object> reportingGuidance(DouyinLeadAcquisitionRunResponse result) {
        List<JsonNode> collectedEvents = eventPayloads(result, "lead.comments.collected");
        Map<String, Object> out = new LinkedHashMap<>();
        if (collectedEvents.isEmpty()) {
            out.put("collectionStatus", "not_reported");
            out.put("allowedSummary", "No comment collection event was returned; do not report collection totals.");
            out.put("unsupportedConclusions", unsupportedConclusions());
            return out;
        }
        JsonNode collected = collectedEvents.getLast();
        JsonNode runSummary = latestEventPayload(result, "lead.run.summary");
        boolean multiVideo = collectedEvents.size() > 1
                || runSummary != null && runSummary.path("processedVideos").asInt(0) > 1;
        boolean complete = collectedEvents.stream().allMatch(event -> event.path("complete").asBoolean(false));
        int declared = aggregateCount(runSummary, collectedEvents, "declaredCommentCount");
        int collectedCount = aggregateCount(runSummary, collectedEvents, "commentsCollected");
        String stopReason = collected.path("stopReason").asText("");
        out.put("collectionStatus", complete ? "complete" : "partial_unverified");
        out.put("complete", complete);
        out.put("multiVideo", multiVideo);
        out.put("collectionEventCount", collectedEvents.size());
        out.put("declaredCommentCount", declared);
        out.put("commentsCollected", collectedCount);
        out.put("collectionCoverage", aggregateCoverage(runSummary, declared, collectedCount));
        out.put("remainingDeclaredComments", declared > 0 ? Math.max(0, declared - collectedCount) : 0);
        out.put("stopReason", stopReason);
        out.put("stopReasons", stopReasons(collectedEvents));
        out.put("effectiveScrolls", sumEventInt(collectedEvents, "effectiveScrolls"));
        out.put("advancedWindows", sumEventInt(collectedEvents, "advancedWindows"));
        out.put("totalNewItems", sumEventInt(collectedEvents, "totalNewItems"));
        out.put("stableNoNewWindows", sumEventInt(collectedEvents, "stableNoNewWindows"));
        boolean bottomConfirmed = collectedEvents.stream()
                .allMatch(event -> isEndOfListStop(event.path("stopReason").asText("")));
        boolean topLevelComplete = collectedEvents.stream()
                .allMatch(event -> event.path("topLevelCollectionComplete").asBoolean(false)
                        || event.path("complete").asBoolean(false));
        boolean declaredTotalMayIncludeReplies = collectedEvents.stream()
                .anyMatch(event -> event.path("declaredTotalMayIncludeReplies").asBoolean(false));
        out.put("topLevelCollectionComplete", topLevelComplete);
        out.put("declaredCountMismatch", declared > 0 && collectedCount < declared);
        out.put("declaredTotalMayIncludeReplies", declaredTotalMayIncludeReplies);
        out.put("replyExpansionMode", collected.path("replyExpansionMode").asText(""));
        out.put("bottomConfirmed", bottomConfirmed);
        out.put("perVideoCollections", perVideoCollections(collectedEvents));
        out.put("allowedSummary", multiVideo
                ? "This is a multi-video V2 run. Report aggregate declaredCommentCount and commentsCollected from reportingGuidance, not the latest per-video lead.comments.collected event. Use perVideoCollections only for per-video rows. If declared and collected counts differ while topLevelCollectionComplete=true, state that V1 reply expansion is disabled and declared totals may include collapsed replies."
                : complete && declaredTotalMayIncludeReplies
                ? "The comment list bottom marker was observed and V1 completed top-level DOM comment collection. The declared total may include collapsed replies because reply expansion is disabled; report declaredCommentCount, commentsCollected, stopReason, and that replies were not expanded."
                : complete
                ? "Comment collection reached a defined completion condition. Report the exact stopReason."
                : bottomConfirmed
                ? "The bottom marker was observed, but declared and collected counts do not match. Report it as incomplete and include declaredCommentCount, commentsCollected, remainingDeclaredComments, and stopReason."
                : "Automatic collection is incomplete. Report only the observed counts and stopReason; say the workflow did not confirm the bottom of the comment list.");
        out.put("unsupportedConclusions", unsupportedConclusions());
        return out;
    }

    private int aggregateCount(JsonNode runSummary, List<JsonNode> collectedEvents, String fieldName) {
        if (runSummary != null && runSummary.has(fieldName)) {
            return Math.max(0, runSummary.path(fieldName).asInt(0));
        }
        return sumEventInt(collectedEvents, fieldName);
    }

    private double aggregateCoverage(JsonNode runSummary, int declared, int collected) {
        if (runSummary != null && runSummary.has("collectionCoverage")) {
            return runSummary.path("collectionCoverage").asDouble(0.0d);
        }
        return declared > 0 ? Math.min(1.0d, collected / (double) declared) : 0.0d;
    }

    private int sumEventInt(List<JsonNode> events, String fieldName) {
        int total = 0;
        for (JsonNode event : events) {
            total += Math.max(0, event.path(fieldName).asInt(0));
        }
        return total;
    }

    private List<String> stopReasons(List<JsonNode> events) {
        Set<String> reasons = new LinkedHashSet<>();
        for (JsonNode event : events) {
            String reason = event.path("stopReason").asText("");
            if (!reason.isBlank()) {
                reasons.add(reason);
            }
        }
        return List.copyOf(reasons);
    }

    private List<Map<String, Object>> perVideoCollections(List<JsonNode> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("videoIndex", event.path("videoIndex").asInt(0));
            row.put("commentsCollected", event.path("commentsCollected").asInt(0));
            row.put("declaredCommentCount", event.path("declaredCommentCount").asInt(0));
            row.put("collectionCoverage", event.path("collectionCoverage").asDouble(0.0d));
            row.put("complete", event.path("complete").asBoolean(false));
            row.put("stopReason", event.path("stopReason").asText(""));
            row.put("topLevelCollectionComplete", event.path("topLevelCollectionComplete").asBoolean(false));
            row.put("declaredTotalMayIncludeReplies", event.path("declaredTotalMayIncludeReplies").asBoolean(false));
            out.add(row);
        }
        return out;
    }

    private boolean isEndOfListStop(String stopReason) {
        return stopReason != null && stopReason.startsWith("END_OF_LIST");
    }

    private List<String> unsupportedConclusions() {
        return List.of(
                "Do not claim Douyin anti-bot/rate-limit/platform-side limits.",
                "Do not claim the browser was not logged in or login-limited.",
                "Do not claim comments were deleted, hidden, unavailable, or impossible to load.",
                "Do not claim the workflow reached Douyin's loading limit.",
                "Do not call NO_NEW_ITEMS_* a successful full collection.");
    }

    private JsonNode latestEventPayload(DouyinLeadAcquisitionRunResponse result, String type) {
        List<JsonNode> payloads = eventPayloads(result, type);
        return payloads.isEmpty() ? null : payloads.getLast();
    }

    private List<JsonNode> eventPayloads(DouyinLeadAcquisitionRunResponse result, String type) {
        if (result == null || result.events() == null) {
            return List.of();
        }
        List<JsonNode> payloads = new ArrayList<>();
        for (RunTimelineEventDTO event : result.events()) {
            if (event == null || !type.equals(event.type())) {
                continue;
            }
            payloads.add(parsePayload(event.payloadJson()));
        }
        return payloads;
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean isTerminal(String status) {
        return "succeeded".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || "aborted".equalsIgnoreCase(status);
    }

    private String normalizeStatus(String status) {
        return status == null ? "UNKNOWN" : status.toUpperCase(java.util.Locale.ROOT);
    }

    private String messageFor(DouyinLeadAcquisitionRunResponse result, boolean terminal) {
        if ("succeeded".equalsIgnoreCase(result.status())) {
            return "Douyin lead-acquisition workflow completed.";
        }
        if ("failed".equalsIgnoreCase(result.status()) || "aborted".equalsIgnoreCase(result.status())) {
            return "Douyin lead-acquisition workflow stopped before completion. Inspect result.events for the exact failure.";
        }
        return "Douyin lead-acquisition workflow returned status " + normalizeStatus(result.status()) + ".";
    }

    private Map<String, Object> inputMap(DouyinLeadAcquisitionInput input) {
        return Map.of(
                "keyword", input.keyword(),
                "sort", input.sort(),
                "videoLimit", input.videoLimit(),
                "matchRules", input.matchRules(),
                "dmDraft", input.dmDraft(),
                "sendDm", input.sendDm(),
                "engage", input.engage());
    }

    private List<CommentMatchRule> buildMatchRules(String keywordRules,
                                                   String semanticRules,
                                                   String matchProfile,
                                                   String matchDescription,
                                                   String matchExamples,
                                                   Boolean matchHighIntent,
                                                   String highIntentExamples) {
        if (matchDescription != null && !matchDescription.isBlank()) {
            List<CommentMatchRule> rules = new ArrayList<>(DouyinLeadAcquisitionInput.matchTargetRules(
                    matchProfile,
                    matchDescription,
                    splitRules(matchExamples)));
            for (String value : splitRules(keywordRules)) {
                rules.add(CommentMatchRule.keyword(value));
            }
            for (String value : splitRules(semanticRules)) {
                rules.add(CommentMatchRule.semantic(value));
            }
            return rules;
        }
        List<String> examples = highIntentExamples == null || highIntentExamples.isBlank()
                ? DouyinLeadAcquisitionInput.DEFAULT_HIGH_INTENT_EXAMPLES
                : splitRules(highIntentExamples);
        if ((keywordRules == null || keywordRules.isBlank())
                && (semanticRules == null || semanticRules.isBlank())) {
            return Boolean.FALSE.equals(matchHighIntent)
                    ? List.of()
                    : DouyinLeadAcquisitionInput.defaultMatchRules(examples);
        }
        List<CommentMatchRule> rules = new ArrayList<>();
        if (Boolean.TRUE.equals(matchHighIntent)) {
            rules.addAll(DouyinLeadAcquisitionInput.defaultMatchRules(examples));
        }
        for (String value : splitRules(keywordRules)) {
            rules.add(CommentMatchRule.keyword(value));
        }
        for (String value : splitRules(semanticRules)) {
            rules.add(CommentMatchRule.semantic(value));
        }
        return rules;
    }

    private List<String> splitRules(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split("\\s*(?:\\r?\\n|;|；|、|/|，|,)\\s*")) {
            String normalized = part == null ? "" : part.trim();
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private Long parseLongOrDefault(String value, Long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"status\":\"JSON_ERROR\"}";
        }
    }
}
