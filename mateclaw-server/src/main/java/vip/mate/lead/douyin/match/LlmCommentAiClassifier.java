package vip.mate.lead.douyin.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.lead.douyin.model.CommentMatchResult;
import vip.mate.lead.douyin.model.CommentMatchRule;
import vip.mate.lead.douyin.model.DouyinCommentItem;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCommentAiClassifier implements CommentAiClassifier {

    private static final int BATCH_SIZE = 30;
    private static final int MAX_REASON_LENGTH = 120;
    private static final RetryTemplate NO_RETRY = RetryTemplate.builder().maxAttempts(1).build();

    private final ModelConfigService modelConfigService;
    private final ObjectProvider<AgentGraphBuilder> agentGraphBuilderProvider;
    private final ObjectMapper objectMapper;

    @Override
    public List<CommentMatchResult> classify(List<CommentMatchRule> rules, List<CommentMatchResult> candidates) {
        List<CommentMatchRule> semanticRules = CommentMatchRule.normalize(rules).stream()
                .filter(CommentMatchRule::semantic)
                .toList();
        if (semanticRules.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        ChatModel chatModel;
        try {
            ModelConfigEntity model = modelConfigService.getDefaultModel();
            chatModel = agentGraphBuilderProvider.getObject().buildRuntimeChatModel(model, NO_RETRY);
        } catch (Exception e) {
            log.info("[douyin.lead] AI comment classifier unavailable, fallback to rule matcher: {}", e.getMessage());
            return List.of();
        }

        List<CommentMatchResult> out = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, candidates.size());
            List<CommentMatchResult> batch = candidates.subList(start, end);
            try {
                String response = callModel(chatModel, semanticRules, batch);
                out.addAll(parseResponse(semanticRules, batch, response));
            } catch (Exception e) {
                log.info("[douyin.lead] AI comment classifier batch failed, keep rule results: {}", e.getMessage());
            }
        }
        return out;
    }

    private String callModel(ChatModel chatModel, List<CommentMatchRule> rules, List<CommentMatchResult> batch)
            throws JsonProcessingException {
        String system = """
                你是 化帆AI 抖音获客评论筛选器。你的任务是判断每条评论是否符合用户给出的获客匹配规则。
                只根据评论正文判断，不要因为作者名、头像或主页信息而命中。
                不要扩大为泛泛兴趣用户；只有评论表达了与规则一致的需求、痛点、抱怨、购买/咨询意图或明确观点时才命中。
                输出必须是严格 JSON，不要 Markdown，不要解释。格式：
                {"matches":[{"index":0,"matched":true,"score":0.0,"ruleIndex":0,"reason":"简短中文原因"}]}
                score 取 0 到 1；不确定时 matched=false 且 score<=0.64。
                """;
        String user = """
                语义匹配规则 JSON：
                %s

                评论列表 JSON：
                %s
                """.formatted(rulesJson(rules), commentsJson(batch));
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage(user)
        )));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private String rulesJson(List<CommentMatchRule> rules) throws JsonProcessingException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            CommentMatchRule rule = rules.get(i);
            rows.add(Map.of(
                    "index", i,
                    "mode", rule.mode(),
                    "value", rule.value()));
        }
        return objectMapper.writeValueAsString(rows);
    }

    private String commentsJson(List<CommentMatchResult> batch) throws JsonProcessingException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            DouyinCommentItem comment = batch.get(i).comment();
            rows.add(Map.of(
                    "index", i,
                    "author", comment == null ? "" : comment.authorName(),
                    "text", comment == null ? "" : comment.text()));
        }
        return objectMapper.writeValueAsString(rows);
    }

    private List<CommentMatchResult> parseResponse(List<CommentMatchRule> rules, List<CommentMatchResult> batch, String response)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(cleanJson(response));
        JsonNode matches = root.isArray() ? root : root.path("matches");
        if (!matches.isArray()) {
            return List.of();
        }
        Map<Integer, CommentMatchResult> byIndex = new LinkedHashMap<>();
        for (JsonNode node : matches) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= batch.size()) {
                continue;
            }
            CommentMatchResult original = batch.get(index);
            boolean matched = node.path("matched").asBoolean(false);
            double score = clampScore(node.path("score").asDouble(matched ? 0.82d : original.score()));
            if (matched && score < 0.65d) {
                matched = false;
            }
            String reasonText = truncate(node.path("reason").asText(""));
            int ruleIndex = node.path("ruleIndex").asInt(-1);
            String ruleValue = ruleIndex >= 0 && ruleIndex < rules.size() ? rules.get(ruleIndex).value() : "";
            String reason = matched ? "semantic_match" : "semantic_reject";
            if (!ruleValue.isBlank()) {
                reason = reason + ":" + truncate(ruleValue);
            }
            if (!reasonText.isBlank()) {
                reason = reason + " | " + reasonText;
            }
            byIndex.put(index, new CommentMatchResult(original.comment(), matched, score, reason));
        }
        return new ArrayList<>(byIndex.values());
    }

    private String cleanJson(String response) {
        if (response == null || response.isBlank()) {
            return "{}";
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private double clampScore(double value) {
        if (!Double.isFinite(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(0.99d, value));
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= MAX_REASON_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_REASON_LENGTH);
    }
}
