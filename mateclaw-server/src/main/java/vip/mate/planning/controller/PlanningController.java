package vip.mate.planning.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.planning.model.PlanEntity;
import vip.mate.planning.service.PlanningService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/**
 * 任务规划接口
 *
 * @author MateClaw Team
 */
@Tag(name = "任务规划")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanningController {

    private final PlanningService planningService;

    @Operation(summary = "获取 Agent 的计划列表")
    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<PlanEntity>> listByAgent(
            @RequestParam String agentId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(planningService.listPlansByAgent(agentId, workspaceId == null ? 1L : workspaceId));
    }

    @Operation(summary = "获取计划详情（含步骤）")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("viewer")
    public R<PlanEntity> getPlan(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(planningService.getPlanWithSteps(id, workspaceId == null ? 1L : workspaceId));
    }
}
