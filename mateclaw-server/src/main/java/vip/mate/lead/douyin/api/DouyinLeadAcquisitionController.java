package vip.mate.lead.douyin.api;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.lead.douyin.DouyinLeadAcquisitionRunService;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lead-acquisition")
public class DouyinLeadAcquisitionController {

    private final DouyinLeadAcquisitionRunService runService;
    private final DouyinLeadAcquisitionQueryService queryService;
    private final DouyinLeadAcquisitionEventStreamService eventStreamService;
    private final DouyinLeadTemplateService templateService;
    private final AuthService authService;

    public DouyinLeadAcquisitionController(DouyinLeadAcquisitionRunService runService,
                                           DouyinLeadAcquisitionQueryService queryService,
                                           DouyinLeadAcquisitionEventStreamService eventStreamService,
                                           DouyinLeadTemplateService templateService,
                                           AuthService authService) {
        this.runService = runService;
        this.queryService = queryService;
        this.eventStreamService = eventStreamService;
        this.templateService = templateService;
        this.authService = authService;
    }

    @PostMapping("/douyin/runs")
    @RequireWorkspaceRole("member")
    public R<DouyinLeadAcquisitionRunResponse> start(
            @RequestBody(required = false) DouyinLeadAcquisitionStartRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {
        UserEntity user = requireUser(auth);
        DouyinLeadAcquisitionInput input = normalizeStartRequest(request);
        return R.ok(runService.start(workspaceId == null ? 1L : workspaceId, user.getId(), input));
    }

    @GetMapping("/douyin/runs")
    @RequireWorkspaceRole("viewer")
    public R<List<DouyinLeadRunListItem>> recentRuns(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return R.ok(queryService.recentRuns(workspaceId == null ? 1L : workspaceId, limit));
    }

    @GetMapping("/douyin/leads")
    @RequireWorkspaceRole("viewer")
    public R<List<DouyinLeadPoolItem>> leadPool(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "taskId", required = false) Long taskId) {
        return R.ok(queryService.leadPool(workspaceId == null ? 1L : workspaceId, limit, status, keyword, taskId));
    }

    @GetMapping("/douyin/stats")
    @RequireWorkspaceRole("viewer")
    public R<DouyinLeadStatsDTO> stats(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return R.ok(queryService.stats(workspaceId == null ? 1L : workspaceId, limit, keyword));
    }

    @GetMapping("/douyin/templates")
    @RequireWorkspaceRole("viewer")
    public R<List<DouyinLeadTemplateDTO>> templates(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(templateService.list(workspaceId == null ? 1L : workspaceId));
    }

    @PostMapping("/douyin/templates")
    @RequireWorkspaceRole("member")
    public R<DouyinLeadTemplateDTO> createTemplate(
            @RequestBody DouyinLeadTemplateRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication auth) {
        UserEntity user = requireUser(auth);
        return R.ok(templateService.create(workspaceId == null ? 1L : workspaceId, user.getId(), request));
    }

    @PutMapping("/douyin/templates/{id}")
    @RequireWorkspaceRole("member")
    public R<DouyinLeadTemplateDTO> updateTemplate(
            @PathVariable Long id,
            @RequestBody DouyinLeadTemplateRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(templateService.update(workspaceId == null ? 1L : workspaceId, id, request));
    }

    @DeleteMapping("/douyin/templates/{id}")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> deleteTemplate(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        templateService.delete(workspaceId == null ? 1L : workspaceId, id);
        return R.ok(Map.of("deleted", true, "id", String.valueOf(id)));
    }

    @GetMapping("/runs/{runId}")
    @RequireWorkspaceRole("viewer")
    public R<DouyinLeadAcquisitionRunResponse> run(@PathVariable Long runId) {
        return R.ok(queryService.byRun(runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> cancel(@PathVariable Long runId) {
        runService.cancel(runId);
        return R.ok(Map.of("cancelled", true, "runId", String.valueOf(runId)));
    }

    @GetMapping(value = "/runs/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequireWorkspaceRole("viewer")
    public SseEmitter streamEvents(@PathVariable Long runId,
                                   @RequestParam(value = "afterEventId", required = false) Long afterEventId,
                                   @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return eventStreamService.stream(runId, lastEventId, afterEventId);
    }

    @GetMapping("/tasks/{taskId}")
    @RequireWorkspaceRole("viewer")
    public R<DouyinLeadAcquisitionRunResponse> task(@PathVariable Long taskId) {
        return R.ok(queryService.byTask(taskId));
    }

    @GetMapping("/tasks/{taskId}/comments")
    @RequireWorkspaceRole("viewer")
    public R<List<LeadCommentDTO>> comments(@PathVariable Long taskId) {
        return R.ok(queryService.comments(taskId));
    }

    @GetMapping("/tasks/{taskId}/profiles")
    @RequireWorkspaceRole("viewer")
    public R<List<LeadProfileDTO>> profiles(@PathVariable Long taskId) {
        return R.ok(queryService.profiles(taskId));
    }

    @GetMapping("/tasks/{taskId}/engagements")
    @RequireWorkspaceRole("viewer")
    public R<List<LeadEngagementDTO>> engagements(@PathVariable Long taskId) {
        return R.ok(queryService.engagements(taskId));
    }

    private UserEntity requireUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new MateClawException("err.auth.unauthenticated", "Authentication required");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", "Authenticated user not found");
        }
        return user;
    }

    private DouyinLeadAcquisitionInput normalizeStartRequest(DouyinLeadAcquisitionStartRequest request) {
        if (request == null) {
            throw new MateClawException("err.lead.douyin.keyword_required", "Douyin keyword is required");
        }
        return request.normalized();
    }

}
