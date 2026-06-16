package vip.mate.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiTransformationAggregator;
import vip.mate.wiki.service.WikiTransformationExecutor;
import vip.mate.wiki.service.WikiTransformationService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WikiTransformationControllerTest {

    private WikiTransformationService transformationService;
    private WikiKnowledgeBaseService kbService;
    private WikiTransformationController controller;

    @BeforeEach
    void setUp() {
        transformationService = mock(WikiTransformationService.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        controller = new WikiTransformationController(
                transformationService,
                mock(WikiTransformationExecutor.class),
                mock(WikiTransformationAggregator.class),
                kbService);
    }

    @Test
    void applyMissingTemplateReturns404Envelope() {
        when(transformationService.getById(99L)).thenReturn(null);

        R<WikiTransformationRunEntity> response = controller.apply(
                99L, Map.of("rawId", 1L), false, 1L);

        assertEquals(404, response.getCode());
    }

    @Test
    void applyWithRawIdAndPageIdReturns400Envelope() {
        WikiTransformationEntity transformation = new WikiTransformationEntity();
        transformation.setId(99L);
        transformation.setWorkspaceId(1L);
        when(transformationService.getById(99L)).thenReturn(transformation);

        R<WikiTransformationRunEntity> response = controller.apply(
                99L, Map.of("rawId", 1L, "pageId", 2L), false, 1L);

        assertEquals(400, response.getCode());
    }

    @Test
    void listFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.list(null, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(transformationService);
    }

    @Test
    void getFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.get(99L, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(transformationService);
    }

    @Test
    void getRunFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.getRun(99L, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(transformationService);
    }

    @Test
    void listRunsByRawRejectsRunsFromDifferentWorkspace() {
        WikiTransformationRunEntity foreignRun = new WikiTransformationRunEntity();
        foreignRun.setId(7L);
        foreignRun.setRawId(55L);
        foreignRun.setKbId(88L);
        foreignRun.setWorkspaceId(2L);
        when(transformationService.listRunsByRaw(55L, 50)).thenReturn(List.of(foreignRun));

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.listRuns(55L, null, null, 50, 1L));

        assertEquals(403, ex.getCode());
        assertEquals("err.common.wrong_workspace", ex.getMsgKey());
    }

    @Test
    void listRunsByRawVerifiesKbWorkspaceWhenRunWorkspaceIsMissing() {
        WikiTransformationRunEntity run = new WikiTransformationRunEntity();
        run.setId(7L);
        run.setRawId(55L);
        run.setKbId(88L);
        when(transformationService.listRunsByRaw(55L, 50)).thenReturn(List.of(run));
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(88L);
        kb.setWorkspaceId(2L);
        when(kbService.getById(88L)).thenReturn(kb);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.listRuns(55L, null, null, 50, 1L));

        assertEquals(403, ex.getCode());
        assertEquals("err.common.wrong_workspace", ex.getMsgKey());
    }
}
