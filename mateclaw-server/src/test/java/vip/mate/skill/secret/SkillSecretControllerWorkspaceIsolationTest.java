package vip.mate.skill.secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cross-workspace guard for {@code /api/v1/skills/{skillId}/secrets}.
 *
 * <p>The endpoints carry {@code @RequireWorkspaceRole("admin")}, which only
 * proves the caller administers the workspace named in {@code X-Workspace-Id}.
 * It does NOT prove the {@code skillId} belongs to that workspace — so a
 * workspace admin could read masked previews of, overwrite, or delete the
 * AES-encrypted credentials of another workspace's custom skill. These tests
 * pin the ownership check that closes the leak.
 */
class SkillSecretControllerWorkspaceIsolationTest {

    private SkillSecretService secretService;
    private SkillService skillService;
    private SkillSecretController controller;

    @BeforeEach
    void setUp() {
        secretService = mock(SkillSecretService.class);
        skillService = mock(SkillService.class);
        controller = new SkillSecretController(secretService, skillService);
    }

    private SkillEntity skill(Long workspaceId, boolean builtin) {
        SkillEntity skill = new SkillEntity();
        skill.setId(7L);
        skill.setWorkspaceId(workspaceId);
        skill.setBuiltin(builtin);
        return skill;
    }

    @Test
    void putRejectsSecretWriteToSkillFromAnotherWorkspace() {
        when(skillService.getSkill(7L)).thenReturn(skill(99L, false));

        assertThrows(MateClawException.class,
                () -> controller.put(7L, Map.of("key", "API_KEY", "value", "secret"), 1L));
        verify(secretService, never()).put(any(), any(), any());
    }

    @Test
    void listRejectsSkillFromAnotherWorkspace() {
        when(skillService.getSkill(7L)).thenReturn(skill(99L, false));

        assertThrows(MateClawException.class, () -> controller.list(7L, 1L));
        verify(secretService, never()).listSummaries(any());
    }

    @Test
    void removeRejectsSkillFromAnotherWorkspace() {
        when(skillService.getSkill(7L)).thenReturn(skill(99L, false));

        assertThrows(MateClawException.class, () -> controller.remove(7L, "API_KEY", 1L));
        verify(secretService, never()).remove(any(), any());
    }

    @Test
    void putAllowsSecretWriteToSkillInOwnWorkspace() {
        when(skillService.getSkill(7L)).thenReturn(skill(1L, false));

        controller.put(7L, Map.of("key", "API_KEY", "value", "secret"), 1L);

        verify(secretService).put(eq(7L), eq("API_KEY"), eq("secret"));
    }

    @Test
    void listAllowsBuiltinSkillRegardlessOfWorkspace() {
        when(skillService.getSkill(7L)).thenReturn(skill(null, true));
        when(secretService.listSummaries(7L)).thenReturn(List.of());

        controller.list(7L, 1L);

        verify(secretService).listSummaries(7L);
    }
}
