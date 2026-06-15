package vip.mate.lead.douyin.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vip.mate.os.run.model.AgentRunStatus;
import vip.mate.os.run.model.AgentEventEntity;
import vip.mate.os.run.model.AgentRunEntity;
import vip.mate.os.run.model.LeadCommentEntity;
import vip.mate.os.run.model.LeadEngagementEntity;
import vip.mate.os.run.model.LeadProfileEntity;
import vip.mate.os.run.model.LeadTaskEntity;
import vip.mate.os.run.repository.AgentEventMapper;
import vip.mate.os.run.repository.AgentRunMapper;
import vip.mate.os.run.repository.LeadCommentMapper;
import vip.mate.os.run.repository.LeadEngagementMapper;
import vip.mate.os.run.repository.LeadProfileMapper;
import vip.mate.os.run.repository.LeadTaskMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DouyinLeadAcquisitionQueryService {

    private final AgentRunMapper runMapper;
    private final AgentEventMapper eventMapper;
    private final LeadTaskMapper taskMapper;
    private final LeadCommentMapper commentMapper;
    private final LeadProfileMapper profileMapper;
    private final LeadEngagementMapper engagementMapper;
    private final ObjectMapper objectMapper;

    public DouyinLeadAcquisitionQueryService(AgentRunMapper runMapper,
                                             AgentEventMapper eventMapper,
                                             LeadTaskMapper taskMapper,
                                             LeadCommentMapper commentMapper,
                                             LeadProfileMapper profileMapper,
                                             LeadEngagementMapper engagementMapper,
                                             ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.commentMapper = commentMapper;
        this.profileMapper = profileMapper;
        this.engagementMapper = engagementMapper;
        this.objectMapper = objectMapper;
    }

    public DouyinLeadAcquisitionRunResponse byRun(Long runId) {
        AgentRunEntity run = requireRun(runId);
        LeadTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<LeadTaskEntity>()
                .eq(LeadTaskEntity::getRunId, runId)
                .last("LIMIT 1"));
        Long taskId = task == null ? null : task.getId();
        List<LeadCommentDTO> comments = taskId == null ? List.of() : comments(taskId);
        List<LeadCommentDTO> matches = comments.stream().filter(LeadCommentDTO::matched).toList();
        List<LeadEngagementDTO> engagements = taskId == null ? List.of() : engagements(taskId);
        JsonNode summary = parseSummary(task);
        int commentsCollected = summary.path("commentsCollected").asInt(comments.size());
        int declaredCommentCount = summary.path("declaredCommentCount")
                .asInt(sumVideoResultInt(summary, "declaredCommentCount"));
        int remainingDeclaredComments = summary.path("remainingDeclaredComments").asInt(
                declaredCommentCount > 0 ? Math.max(0, declaredCommentCount - commentsCollected) : 0);
        return new DouyinLeadAcquisitionRunResponse(
                String.valueOf(run.getId()),
                taskId == null ? null : String.valueOf(taskId),
                run.getStatus(),
                commentsCollected,
                declaredCommentCount,
                remainingDeclaredComments,
                summary.path("collectionCoverage").asDouble(declaredCommentCount > 0
                        ? Math.min(1.0d, commentsCollected / (double) declaredCommentCount)
                        : 0.0d),
                summary.path("matchedComments").asInt(matches.size()),
                summary.path("requestedVideoLimit").asInt(0),
                summary.path("processedVideos").asInt(0),
                summary.path("succeededVideos").asInt(0),
                summary.path("failedVideos").asInt(0),
                summary.path("engagementsCreated").asInt(engagements.size()),
                comments,
                matches,
                engagements,
                events(runId));
    }

    public DouyinLeadAcquisitionRunResponse byTask(Long taskId) {
        LeadTaskEntity task = requireTask(taskId);
        return byRun(task.getRunId());
    }

    public List<DouyinLeadRunListItem> recentRuns(Long workspaceId, int limit) {
        int boundedLimit = Math.max(1, Math.min(50, limit));
        LambdaQueryWrapper<LeadTaskEntity> query = new LambdaQueryWrapper<LeadTaskEntity>()
                .eq(LeadTaskEntity::getPlatform, "douyin")
                .orderByDesc(LeadTaskEntity::getUpdateTime)
                .orderByDesc(LeadTaskEntity::getCreateTime);
        if (workspaceId != null) {
            query.eq(LeadTaskEntity::getWorkspaceId, workspaceId);
        }
        query.last("LIMIT " + boundedLimit);

        List<DouyinLeadRunListItem> rows = new ArrayList<>();
        for (LeadTaskEntity task : taskMapper.selectList(query)) {
            AgentRunEntity run = task.getRunId() == null ? null : runMapper.selectById(task.getRunId());
            JsonNode summary = parseSummary(task);
            JsonNode input = parseJson(task.getInputJson());
            int commentsCollected = summary.path("commentsCollected").asInt(countComments(task.getId(), false));
            int matchedComments = summary.path("matchedComments").asInt(countComments(task.getId(), true));
            int engagementsCreated = summary.path("engagementsCreated").asInt(countEngagements(task.getId()));
            rows.add(new DouyinLeadRunListItem(
                    DouyinLeadAcquisitionRunResponse.id(task.getRunId()),
                    DouyinLeadAcquisitionRunResponse.id(task.getId()),
                    task.getKeyword(),
                    task.getSortMode(),
                    run == null || run.getStatus() == null ? task.getStatus() : run.getStatus(),
                    summary.path("requestedVideoLimit").asInt(input.path("videoLimit").asInt(0)),
                    summary.path("processedVideos").asInt(0),
                    summary.path("failedVideos").asInt(0),
                    commentsCollected,
                    matchedComments,
                    engagementsCreated,
                    run == null ? null : run.getFailureCode(),
                    run == null ? null : run.getFailureMessage(),
                    task.getCreateTime() == null ? null : task.getCreateTime().toString(),
                    task.getUpdateTime() == null ? null : task.getUpdateTime().toString()));
        }
        return rows;
    }

    public DouyinLeadStatsDTO stats(Long workspaceId, int limit, String keyword) {
        int boundedLimit = Math.max(1, Math.min(200, limit));
        LambdaQueryWrapper<LeadTaskEntity> taskQuery = new LambdaQueryWrapper<LeadTaskEntity>()
                .eq(LeadTaskEntity::getPlatform, "douyin")
                .orderByDesc(LeadTaskEntity::getUpdateTime)
                .orderByDesc(LeadTaskEntity::getCreateTime)
                .last("LIMIT " + boundedLimit);
        if (workspaceId != null) {
            taskQuery.eq(LeadTaskEntity::getWorkspaceId, workspaceId);
        }
        if (keyword != null && !keyword.isBlank()) {
            taskQuery.like(LeadTaskEntity::getKeyword, keyword.trim());
        }

        List<LeadTaskEntity> tasks = taskMapper.selectList(taskQuery);
        if (tasks.isEmpty()) {
            return DouyinLeadStatsDTO.empty();
        }

        List<Long> taskIds = new ArrayList<>();
        Set<Long> runIds = new HashSet<>();
        for (LeadTaskEntity task : tasks) {
            if (task.getId() != null) {
                taskIds.add(task.getId());
            }
            if (task.getRunId() != null) {
                runIds.add(task.getRunId());
            }
        }

        Map<Long, AgentRunEntity> runsById = new LinkedHashMap<>();
        if (!runIds.isEmpty()) {
            for (AgentRunEntity run : runMapper.selectBatchIds(runIds)) {
                if (run.getId() != null) {
                    runsById.put(run.getId(), run);
                }
            }
        }

        Map<Long, Integer> engagementsByTask = new LinkedHashMap<>();
        Map<String, Integer> failureCounts = new LinkedHashMap<>();
        int sentMessages = 0;
        if (!taskIds.isEmpty()) {
            List<LeadEngagementEntity> engagements = engagementMapper.selectList(new LambdaQueryWrapper<LeadEngagementEntity>()
                    .in(LeadEngagementEntity::getTaskId, taskIds));
            for (LeadEngagementEntity engagement : engagements) {
                if (engagement.getTaskId() != null) {
                    engagementsByTask.merge(engagement.getTaskId(), 1, Integer::sum);
                }
                if (isMessageSent(engagement)) {
                    sentMessages++;
                }
                if (isFailedEngagement(engagement)) {
                    addFailure(failureCounts, firstNonBlank(
                            engagement.getFailureCode(),
                            engagement.getFailureMessage(),
                            "ENGAGEMENT_FAILED"));
                }
            }
        }

        int runningTasks = 0;
        int succeededTasks = 0;
        int failedTasks = 0;
        int requestedVideos = 0;
        int processedVideos = 0;
        int succeededVideos = 0;
        int failedVideos = 0;
        int commentsCollected = 0;
        int matchedComments = 0;
        int engagementsCreated = 0;

        for (LeadTaskEntity task : tasks) {
            AgentRunEntity run = task.getRunId() == null ? null : runsById.get(task.getRunId());
            String status = run == null || run.getStatus() == null ? task.getStatus() : run.getStatus();
            if (isRunningStatus(status)) {
                runningTasks++;
            } else if (isSucceededStatus(status)) {
                succeededTasks++;
            } else if (isFailedStatus(status)) {
                failedTasks++;
                if (run != null) {
                    addFailure(failureCounts, firstNonBlank(
                            run.getFailureCode(),
                            run.getFailureMessage(),
                            "RUN_FAILED"));
                }
            }

            JsonNode summary = parseSummary(task);
            JsonNode input = parseJson(task.getInputJson());
            requestedVideos += summary.path("requestedVideoLimit").asInt(input.path("videoLimit").asInt(0));
            processedVideos += summary.path("processedVideos").asInt(countVideoResults(summary));
            succeededVideos += summary.path("succeededVideos").asInt(countVideoResultsByStatus(summary, "succeeded"));
            failedVideos += summary.path("failedVideos").asInt(countVideoFailures(summary));
            commentsCollected += summaryIntOrCommentCount(summary, "commentsCollected", task.getId(), false);
            matchedComments += summaryIntOrCommentCount(summary, "matchedComments", task.getId(), true);
            engagementsCreated += summaryIntOrDefault(summary, "engagementsCreated",
                    engagementsByTask.getOrDefault(task.getId(), 0));
            collectVideoFailures(summary, failureCounts);
        }

        List<DouyinLeadStatsDTO.FailureReason> failureReasons = failureCounts.entrySet().stream()
                .map(entry -> new DouyinLeadStatsDTO.FailureReason(entry.getKey(), entry.getValue()))
                .toList();
        return new DouyinLeadStatsDTO(
                tasks.size(),
                runningTasks,
                succeededTasks,
                failedTasks,
                requestedVideos,
                processedVideos,
                succeededVideos,
                failedVideos,
                commentsCollected,
                matchedComments,
                engagementsCreated,
                sentMessages,
                commentsCollected > 0 ? matchedComments / (double) commentsCollected : 0.0d,
                matchedComments > 0 ? engagementsCreated / (double) matchedComments : 0.0d,
                engagementsCreated > 0 ? sentMessages / (double) engagementsCreated : 0.0d,
                failureReasons);
    }

    public List<DouyinLeadPoolItem> leadPool(Long workspaceId, int limit, String status, String keyword, Long taskId) {
        int boundedLimit = Math.max(1, Math.min(100, limit));
        int taskScanLimit = Math.max(50, Math.min(250, boundedLimit * 5));
        int commentScanLimit = Math.max(boundedLimit, Math.min(500, boundedLimit * 5));
        String statusFilter = status == null || status.isBlank() ? "all" : status.trim().toLowerCase();
        LambdaQueryWrapper<LeadTaskEntity> taskQuery = new LambdaQueryWrapper<LeadTaskEntity>()
                .eq(LeadTaskEntity::getPlatform, "douyin")
                .orderByDesc(LeadTaskEntity::getUpdateTime)
                .orderByDesc(LeadTaskEntity::getCreateTime)
                .last("LIMIT " + taskScanLimit);
        if (workspaceId != null) {
            taskQuery.eq(LeadTaskEntity::getWorkspaceId, workspaceId);
        }
        if (taskId != null) {
            taskQuery.eq(LeadTaskEntity::getId, taskId);
        }
        if (keyword != null && !keyword.isBlank()) {
            taskQuery.like(LeadTaskEntity::getKeyword, keyword.trim());
        }

        List<LeadTaskEntity> tasks = taskMapper.selectList(taskQuery);
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, LeadTaskEntity> tasksById = new LinkedHashMap<>();
        Set<Long> runIds = new HashSet<>();
        List<Long> taskIds = new ArrayList<>();
        for (LeadTaskEntity task : tasks) {
            if (task.getId() == null) {
                continue;
            }
            tasksById.put(task.getId(), task);
            taskIds.add(task.getId());
            if (task.getRunId() != null) {
                runIds.add(task.getRunId());
            }
        }
        if (taskIds.isEmpty()) {
            return List.of();
        }

        Map<Long, AgentRunEntity> runsById = new LinkedHashMap<>();
        if (!runIds.isEmpty()) {
            for (AgentRunEntity run : runMapper.selectBatchIds(runIds)) {
                if (run.getId() != null) {
                    runsById.put(run.getId(), run);
                }
            }
        }

        List<LeadCommentEntity> comments = commentMapper.selectList(new LambdaQueryWrapper<LeadCommentEntity>()
                .in(LeadCommentEntity::getTaskId, taskIds)
                .eq(LeadCommentEntity::getMatched, true)
                .orderByDesc(LeadCommentEntity::getCreateTime)
                .last("LIMIT " + commentScanLimit));
        if (comments.isEmpty()) {
            return List.of();
        }

        Set<Long> commentIds = new HashSet<>();
        for (LeadCommentEntity comment : comments) {
            if (comment.getId() != null) {
                commentIds.add(comment.getId());
            }
        }

        Map<Long, LeadEngagementEntity> engagementsByCommentId = new LinkedHashMap<>();
        Set<Long> profileIds = new HashSet<>();
        if (!commentIds.isEmpty()) {
            List<LeadEngagementEntity> engagements = engagementMapper.selectList(new LambdaQueryWrapper<LeadEngagementEntity>()
                    .in(LeadEngagementEntity::getCommentId, commentIds)
                    .orderByDesc(LeadEngagementEntity::getUpdateTime)
                    .orderByDesc(LeadEngagementEntity::getId));
            for (LeadEngagementEntity engagement : engagements) {
                if (engagement.getCommentId() != null) {
                    engagementsByCommentId.putIfAbsent(engagement.getCommentId(), engagement);
                }
                if (engagement.getProfileId() != null) {
                    profileIds.add(engagement.getProfileId());
                }
            }
        }

        Map<Long, LeadProfileEntity> profilesById = new LinkedHashMap<>();
        if (!profileIds.isEmpty()) {
            for (LeadProfileEntity profile : profileMapper.selectBatchIds(profileIds)) {
                if (profile.getId() != null) {
                    profilesById.put(profile.getId(), profile);
                }
            }
        }

        List<DouyinLeadPoolItem> rows = new ArrayList<>();
        for (LeadCommentEntity comment : comments) {
            LeadTaskEntity task = comment.getTaskId() == null ? null : tasksById.get(comment.getTaskId());
            if (task == null) {
                continue;
            }
            AgentRunEntity run = task.getRunId() == null ? null : runsById.get(task.getRunId());
            LeadEngagementEntity engagement = comment.getId() == null ? null : engagementsByCommentId.get(comment.getId());
            if (!matchesLeadStatus(statusFilter, engagement)) {
                continue;
            }
            LeadProfileEntity profile = engagement == null || engagement.getProfileId() == null
                    ? null
                    : profilesById.get(engagement.getProfileId());
            rows.add(new DouyinLeadPoolItem(
                    DouyinLeadAcquisitionRunResponse.id(task.getRunId()),
                    DouyinLeadAcquisitionRunResponse.id(task.getId()),
                    task.getKeyword(),
                    task.getSortMode(),
                    run == null || run.getStatus() == null ? task.getStatus() : run.getStatus(),
                    DouyinLeadAcquisitionRunResponse.id(comment.getId()),
                    comment.getCommentKey(),
                    comment.getVideoKey(),
                    comment.getAuthorName(),
                    comment.getAuthorProfileUrl(),
                    comment.getCommentText(),
                    comment.getMatchScore(),
                    comment.getMatchReason(),
                    engagement == null ? null : DouyinLeadAcquisitionRunResponse.id(engagement.getId()),
                    engagement == null ? null : DouyinLeadAcquisitionRunResponse.id(engagement.getProfileId()),
                    profile == null ? null : profile.getProfileUrl(),
                    profile == null ? null : profile.getDisplayName(),
                    engagement == null ? null : engagement.getEngagementType(),
                    engagement == null ? "pending" : engagement.getStatus(),
                    isSent(engagement),
                    engagement == null ? null : engagement.getDraftText(),
                    engagement == null ? null : engagement.getFailureCode(),
                    engagement == null ? null : engagement.getFailureMessage(),
                    engagement == null ? null : engagement.getEvidenceRef(),
                    comment.getCreateTime() == null ? null : comment.getCreateTime().toString(),
                    engagement != null && engagement.getUpdateTime() != null
                            ? engagement.getUpdateTime().toString()
                            : comment.getCreateTime() == null ? null : comment.getCreateTime().toString()));
            if (rows.size() >= boundedLimit) {
                return rows;
            }
        }
        return rows;
    }

    public List<RunTimelineEventDTO> eventsSince(Long runId, Long afterEventId) {
        LambdaQueryWrapper<AgentEventEntity> query = new LambdaQueryWrapper<AgentEventEntity>()
                .eq(AgentEventEntity::getRunId, runId)
                .orderByAsc(AgentEventEntity::getId);
        if (afterEventId != null && afterEventId > 0) {
            query.gt(AgentEventEntity::getId, afterEventId);
        }
        return eventMapper.selectList(query)
                .stream()
                .map(RunTimelineEventDTO::from)
                .toList();
    }

    public boolean isRunTerminal(Long runId) {
        AgentRunEntity run = requireRun(runId);
        return AgentRunStatus.parse(run.getStatus()).isTerminal();
    }

    public String runStatus(Long runId) {
        return requireRun(runId).getStatus();
    }

    public List<LeadCommentDTO> comments(Long taskId) {
        return commentMapper.selectList(new LambdaQueryWrapper<LeadCommentEntity>()
                        .eq(LeadCommentEntity::getTaskId, taskId)
                        .orderByAsc(LeadCommentEntity::getCreateTime))
                .stream()
                .map(LeadCommentDTO::from)
                .toList();
    }

    public List<LeadProfileDTO> profiles(Long taskId) {
        return profileMapper.selectList(new LambdaQueryWrapper<LeadProfileEntity>()
                        .eq(LeadProfileEntity::getTaskId, taskId)
                        .orderByAsc(LeadProfileEntity::getCreateTime))
                .stream()
                .map(LeadProfileDTO::from)
                .toList();
    }

    public List<LeadEngagementDTO> engagements(Long taskId) {
        return engagementMapper.selectList(new LambdaQueryWrapper<LeadEngagementEntity>()
                        .eq(LeadEngagementEntity::getTaskId, taskId)
                        .orderByAsc(LeadEngagementEntity::getCreateTime))
                .stream()
                .map(LeadEngagementDTO::from)
                .toList();
    }

    public List<RunTimelineEventDTO> events(Long runId) {
        return eventMapper.selectList(new LambdaQueryWrapper<AgentEventEntity>()
                        .eq(AgentEventEntity::getRunId, runId)
                        .orderByAsc(AgentEventEntity::getCreateTime)
                        .orderByAsc(AgentEventEntity::getId))
                .stream()
                .map(RunTimelineEventDTO::from)
                .toList();
    }

    private AgentRunEntity requireRun(Long runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run not found: " + runId);
        }
        return run;
    }

    private LeadTaskEntity requireTask(Long taskId) {
        LeadTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("lead task not found: " + taskId);
        }
        return task;
    }

    private JsonNode parseSummary(LeadTaskEntity task) {
        if (task == null || task.getSummaryJson() == null || task.getSummaryJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        return parseJson(task.getSummaryJson());
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private int sumVideoResultInt(JsonNode summary, String fieldName) {
        JsonNode videoResults = summary.path("videoResults");
        if (!videoResults.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode video : videoResults) {
            total += Math.max(0, video.path(fieldName).asInt(0));
        }
        return total;
    }

    private int countVideoResults(JsonNode summary) {
        JsonNode videoResults = summary.path("videoResults");
        return videoResults.isArray() ? videoResults.size() : 0;
    }

    private int summaryIntOrCommentCount(JsonNode summary, String fieldName, Long taskId, boolean matchedOnly) {
        if (summary.has(fieldName) && !summary.path(fieldName).isNull()) {
            return Math.max(0, summary.path(fieldName).asInt(0));
        }
        return countComments(taskId, matchedOnly);
    }

    private int summaryIntOrDefault(JsonNode summary, String fieldName, int fallback) {
        if (summary.has(fieldName) && !summary.path(fieldName).isNull()) {
            return Math.max(0, summary.path(fieldName).asInt(0));
        }
        return Math.max(0, fallback);
    }

    private int countVideoResultsByStatus(JsonNode summary, String status) {
        JsonNode videoResults = summary.path("videoResults");
        if (!videoResults.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode video : videoResults) {
            if (status.equalsIgnoreCase(video.path("status").asText(""))) {
                total++;
            }
        }
        return total;
    }

    private int countVideoFailures(JsonNode summary) {
        JsonNode videoResults = summary.path("videoResults");
        if (!videoResults.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode video : videoResults) {
            if (isFailedStatus(video.path("status").asText(""))
                    || hasText(video.path("failureCode").asText(""))
                    || hasText(video.path("errorCode").asText(""))) {
                total++;
            }
        }
        return total;
    }

    private void collectVideoFailures(JsonNode summary, Map<String, Integer> failureCounts) {
        JsonNode videoResults = summary.path("videoResults");
        if (!videoResults.isArray()) {
            return;
        }
        for (JsonNode video : videoResults) {
            boolean failed = isFailedStatus(video.path("status").asText(""))
                    || hasText(video.path("failureCode").asText(""))
                    || hasText(video.path("errorCode").asText(""));
            if (failed) {
                addFailure(failureCounts, firstNonBlank(
                        video.path("failureCode").asText(""),
                        video.path("errorCode").asText(""),
                        video.path("stopReason").asText(""),
                        "VIDEO_FAILED"));
            }
        }
    }

    private int countComments(Long taskId, boolean matchedOnly) {
        if (taskId == null) {
            return 0;
        }
        LambdaQueryWrapper<LeadCommentEntity> query = new LambdaQueryWrapper<LeadCommentEntity>()
                .eq(LeadCommentEntity::getTaskId, taskId);
        if (matchedOnly) {
            query.eq(LeadCommentEntity::getMatched, true);
        }
        Long count = commentMapper.selectCount(query);
        return count == null ? 0 : count.intValue();
    }

    private int countEngagements(Long taskId) {
        if (taskId == null) {
            return 0;
        }
        Long count = engagementMapper.selectCount(new LambdaQueryWrapper<LeadEngagementEntity>()
                .eq(LeadEngagementEntity::getTaskId, taskId));
        return count == null ? 0 : count.intValue();
    }

    private boolean matchesLeadStatus(String statusFilter, LeadEngagementEntity engagement) {
        return switch (statusFilter) {
            case "pending" -> engagement == null;
            case "engaged" -> engagement != null;
            case "sent" -> isSent(engagement);
            case "failed" -> engagement != null && ("failed".equalsIgnoreCase(engagement.getStatus())
                    || hasText(engagement.getFailureCode())
                    || hasText(engagement.getFailureMessage()));
            default -> true;
        };
    }

    private boolean isSent(LeadEngagementEntity engagement) {
        return engagement != null
                && ("send_dm".equalsIgnoreCase(engagement.getEngagementType())
                || "dm_draft".equalsIgnoreCase(engagement.getEngagementType()))
                && "succeeded".equalsIgnoreCase(engagement.getStatus());
    }

    private boolean isMessageSent(LeadEngagementEntity engagement) {
        return engagement != null
                && "send_dm".equalsIgnoreCase(engagement.getEngagementType())
                && "succeeded".equalsIgnoreCase(engagement.getStatus());
    }

    private boolean isFailedEngagement(LeadEngagementEntity engagement) {
        return engagement != null
                && ("failed".equalsIgnoreCase(engagement.getStatus())
                || hasText(engagement.getFailureCode())
                || hasText(engagement.getFailureMessage()));
    }

    private boolean isRunningStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        return normalized.isBlank()
                || (!isSucceededStatus(normalized) && !isFailedStatus(normalized));
    }

    private boolean isSucceededStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        return "succeeded".equals(normalized)
                || "success".equals(normalized)
                || "completed".equals(normalized);
    }

    private boolean isFailedStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        return "failed".equals(normalized)
                || "aborted".equals(normalized)
                || "cancelled".equals(normalized)
                || "canceled".equals(normalized);
    }

    private void addFailure(Map<String, Integer> failureCounts, String reason) {
        String normalized = firstNonBlank(reason, "UNKNOWN_FAILURE");
        failureCounts.merge(normalized, 1, Integer::sum);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "UNKNOWN_FAILURE";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "UNKNOWN_FAILURE";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
