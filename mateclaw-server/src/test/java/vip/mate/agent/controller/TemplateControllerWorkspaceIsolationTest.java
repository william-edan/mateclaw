package vip.mate.agent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.service.TemplateService;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TemplateControllerWorkspaceIsolationTest {

    private TemplateService templateService;
    private AuthService authService;
    private TemplateController controller;

    @BeforeEach
    void setUp() {
        templateService = mock(TemplateService.class);
        authService = mock(AuthService.class);
        controller = new TemplateController(templateService, authService);
    }

    @Test
    void applyFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.apply("customer-support", null, "zh-CN", null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(templateService, authService);
    }
}
