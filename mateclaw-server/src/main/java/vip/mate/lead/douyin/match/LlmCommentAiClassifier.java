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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCommentAiClassifier implements CommentAiClassifier {

    // 批次必须让单次输出 JSON 装得进模型 max_tokens(默认 4096);80~100 条会让 deepseek 等
    // 输出被截断成不完整 JSON → 整批失败 → 二分重试,反而跑 3 次。每条输出约 60~80 tokens,
    // 80 条 ≈ 5000~6000 输出 tokens,绝大多数模型 max_tokens 扛得住;偶尔超限被截断由
    // classifyBatchWithFallback 对半拆批自愈(不丢数据)。相较 30 条批数大幅减少 → 更快、更省模型调用。
    // 注意:再调大务必确认所用模型的 max output tokens 够大(单批输出 JSON 不能超,否则被截断成不完整 JSON)。
    private static final int TARGET_BATCH_SIZE = 80;
    private static final int MAX_BATCH_SIZE = 120;
    private static final int MAX_BATCH_CHARS = 12_000;
    // 多批并发上限(LLM 调用 IO 密集),避免 provider 限流的同时让总耗时≈最慢单批而非累加。
    private static final int MAX_CONCURRENT_BATCHES = 4;
    private static final int MAX_COMMENT_TEXT_CHARS = 300;
    private static final int MIN_SPLIT_BATCH_SIZE = 10;
    private static final int COMMENT_JSON_OVERHEAD_CHARS = 80;
    private static final int MAX_REASON_LENGTH = 120;
    private static final RetryTemplate NO_RETRY = RetryTemplate.builder().maxAttempts(1).build();

    private final ModelConfigService modelConfigService;
    private final ObjectProvider<AgentGraphBuilder> agentGraphBuilderProvider;
    private final ObjectMapper objectMapper;

    @Override
    public List<CommentMatchResult> classify(List<CommentMatchRule> rules, List<CommentMatchResult> candidates) {
        return classify(rules, candidates, null);
    }

    @Override
    public List<CommentMatchResult> classify(List<CommentMatchRule> rules,
                                             List<CommentMatchResult> candidates,
                                             BiConsumer<Integer, Integer> onProgress) {
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

        List<List<CommentMatchResult>> batches = planBatches(candidates);
        if (batches.isEmpty()) {
            return List.of();
        }
        int total = candidates.size();
        // 多批可能并发跑,onProgress 会被多线程并发调用;用 AtomicInteger 累加已处理候选数。
        AtomicInteger processed = new AtomicInteger(0);
        if (batches.size() == 1) {
            List<CommentMatchResult> out = classifyBatchWithFallback(chatModel, semanticRules, batches.get(0));
            reportProgress(onProgress, processed, batches.get(0).size(), total);
            return out;
        }
        // 多批并发跑:批次串行时 N 批 × 单批耗时(deepseek 单批 ~25s)会线性累加;并发后
        // 总耗时≈最慢单批。限并发 MAX_CONCURRENT_BATCHES 防 provider 限流。
        Semaphore limit = new Semaphore(MAX_CONCURRENT_BATCHES);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<CommentMatchResult>>> futures = new ArrayList<>();
            for (List<CommentMatchResult> batch : batches) {
                futures.add(executor.submit(() -> {
                    limit.acquire();
                    try {
                        return classifyBatchWithFallback(chatModel, semanticRules, batch);
                    } finally {
                        limit.release();
                        // 每个批次完成后即上报进度(无论成功失败),让 UI 看到推进而非黑盒。
                        reportProgress(onProgress, processed, batch.size(), total);
                    }
                }));
            }
            List<CommentMatchResult> out = new ArrayList<>();
            for (Future<List<CommentMatchResult>> future : futures) {
                try {
                    out.addAll(future.get());
                } catch (Exception e) {
                    log.info("[douyin.lead] AI comment classifier batch failed in parallel pool, keep rule results: {}",
                            e.getMessage());
                }
            }
            return out;
        }
    }

    private void reportProgress(BiConsumer<Integer, Integer> onProgress,
                                AtomicInteger processed,
                                int delta,
                                int total) {
        if (onProgress == null) {
            return;
        }
        int done = Math.min(total, processed.addAndGet(delta));
        onProgress.accept(done, total);
    }

    private List<CommentMatchResult> classifyBatchWithFallback(ChatModel chatModel,
                                                               List<CommentMatchRule> semanticRules,
                                                               List<CommentMatchResult> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        try {
            String response = callModel(chatModel, semanticRules, batch);
            return parseResponse(semanticRules, batch, response);
        } catch (Exception e) {
            if (batch.size() <= MIN_SPLIT_BATCH_SIZE) {
                log.info("[douyin.lead] AI comment classifier batch failed, keep rule results: size={}, error={}",
                        batch.size(), e.getMessage());
                return List.of();
            }
            int splitAt = Math.max(1, batch.size() / 2);
            log.info("[douyin.lead] AI comment classifier batch failed; split and retry: size={}, left={}, right={}, error={}",
                    batch.size(), splitAt, batch.size() - splitAt, e.getMessage());
            List<CommentMatchResult> out = new ArrayList<>();
            out.addAll(classifyBatchWithFallback(chatModel, semanticRules, batch.subList(0, splitAt)));
            out.addAll(classifyBatchWithFallback(chatModel, semanticRules, batch.subList(splitAt, batch.size())));
            return out;
        }
    }

    List<List<CommentMatchResult>> planBatches(List<CommentMatchResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<List<CommentMatchResult>> batches = new ArrayList<>();
        List<CommentMatchResult> current = new ArrayList<>();
        int currentChars = 0;
        for (CommentMatchResult candidate : candidates) {
            int candidateChars = estimateCandidateChars(candidate);
            int countLimit = currentChars <= MAX_BATCH_CHARS / 2 ? MAX_BATCH_SIZE : TARGET_BATCH_SIZE;
            boolean overCount = current.size() >= countLimit;
            boolean overChars = !current.isEmpty() && currentChars + candidateChars > MAX_BATCH_CHARS;
            if (overCount || overChars) {
                batches.add(List.copyOf(current));
                current.clear();
                currentChars = 0;
            }
            current.add(candidate);
            currentChars += candidateChars;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return List.copyOf(batches);
    }

    private int estimateCandidateChars(CommentMatchResult candidate) {
        DouyinCommentItem comment = candidate == null ? null : candidate.comment();
        String author = comment == null ? "" : comment.authorName();
        String text = truncateCommentText(comment == null ? "" : comment.text());
        return COMMENT_JSON_OVERHEAD_CHARS
                + (author == null ? 0 : author.length())
                + text.length();
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
                    "text", truncateCommentText(comment == null ? "" : comment.text())));
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

    String truncateCommentText(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= MAX_COMMENT_TEXT_CHARS) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_COMMENT_TEXT_CHARS);
    }
}
