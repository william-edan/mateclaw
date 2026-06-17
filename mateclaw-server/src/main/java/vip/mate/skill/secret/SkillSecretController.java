package vip.mate.skill.secret;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * RFC-091 settings bridge — admin REST endpoints for managing
 * per-skill secrets independently of the wizard.
 *
 * <p>Lets users edit / delete / re-set credentials after a skill is
 * already installed (e.g. when an API key rotates) without having to
 * tear the skill down and re-run the wizard.
 *
 * <p>Listing returns masked previews only — full plaintext is never
 * shipped over HTTP, even to authenticated callers.
 */
@Tag(name = "Skill Secrets")
@RestController
@RequestMapping("/api/v1/skills/{skillId}/secrets")
@RequiredArgsConstructor
public class SkillSecretController {

    private final SkillSecretService skillSecretService;
    private final SkillService skillService;

    @Operation(summary = "List secret keys + masked previews for a skill")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<SkillSecretService.SecretSummary>> list(
            @PathVariable Long skillId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifySkillWorkspace(skillId, workspaceId);
        return R.ok(skillSecretService.listSummaries(skillId));
    }

    @Operation(summary = "Upsert a secret value (empty value deletes it)")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<Void> put(@PathVariable Long skillId, @RequestBody Map<String, String> body,
                       @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifySkillWorkspace(skillId, workspaceId);
        skillSecretService.put(skillId, body.get("key"), body.get("value"));
        return R.ok();
    }

    @Operation(summary = "Delete a single secret by key")
    @DeleteMapping("/{key}")
    @RequireWorkspaceRole("admin")
    public R<Void> remove(@PathVariable Long skillId, @PathVariable String key,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifySkillWorkspace(skillId, workspaceId);
        skillSecretService.remove(skillId, key);
        return R.ok();
    }

    /**
     * Reject secret operations on a skill that does not belong to the caller's
     * workspace. Built-in skills are global and shared, so they are exempt
     * (mirrors {@code SkillController#verifyResourceWorkspace}).
     */
    private void verifySkillWorkspace(Long skillId, Long headerWorkspaceId) {
        SkillEntity skill = skillService.getSkill(skillId);
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            return;
        }
        long requested = headerWorkspaceId != null
                ? headerWorkspaceId : SkillService.DEFAULT_WORKSPACE_ID;
        long owner = skill.getWorkspaceId() != null
                ? skill.getWorkspaceId() : SkillService.DEFAULT_WORKSPACE_ID;
        if (owner != requested) {
            throw new MateClawException("err.common.wrong_workspace", 403,
                    "Skill " + skillId + " does not belong to the current workspace");
        }
    }
}
