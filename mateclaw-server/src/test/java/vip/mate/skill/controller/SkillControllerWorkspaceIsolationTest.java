package vip.mate.skill.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SkillControllerWorkspaceIsolationTest {

    private SkillService skillService;
    private SkillController controller;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        controller = new SkillController(
                skillService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void createFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.create(null, new SkillEntity()));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(skillService);
    }

    @Test
    void getRealSkillFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.get(42L, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(skillService);
    }

    @Test
    void updateRealSkillFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.update(42L, new SkillEntity(), null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(skillService);
    }
}
