package vip.mate.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiDirectoryScanService;
import vip.mate.wiki.service.WikiEmbeddingService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiLintJobService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiProcessingService;
import vip.mate.wiki.service.WikiRawMaterialService;
import vip.mate.wiki.sse.WikiProgressBus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiControllerWorkspaceIsolationTest {

    @Mock
    private WikiKnowledgeBaseService kbService;

    @Mock
    private WikiRawMaterialService rawService;

    @Mock
    private WikiPageService pageService;

    @Mock
    private WikiProcessingService processingService;

    @Mock
    private WikiDirectoryScanService scanService;

    @Mock
    private WikiEmbeddingService embeddingService;

    @Mock
    private WikiLintJobService lintJobService;

    @Mock
    private WikiProperties properties;

    @Mock
    private WikiProgressBus progressBus;

    @Mock
    private AuditEventService auditEventService;

    private WikiController controller;

    @BeforeEach
    void setUp() {
        controller = new WikiController(
                kbService,
                rawService,
                pageService,
                processingService,
                scanService,
                embeddingService,
                lintJobService,
                properties,
                progressBus,
                auditEventService);
    }

    @Test
    void getKBFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.getKB(1L, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(kbService);
    }

    @Test
    void getKBRejectsKnowledgeBaseFromDifferentWorkspace() {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(1L);
        kb.setWorkspaceId(2L);
        when(kbService.getById(1L)).thenReturn(kb);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.getKB(1L, 1L));

        assertEquals(403, ex.getCode());
        assertEquals("err.common.wrong_workspace", ex.getMsgKey());
    }

    @Test
    void listKBFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.listKBs(null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(kbService);
    }

    @Test
    void lookupPagesFailsClosedWhenWorkspaceHeaderMissing() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.lookupPages("Roadmap", null, null));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.header_required", ex.getMsgKey());
        verifyNoInteractions(kbService, pageService);
    }

    @Test
    void listKBsByAgentFiltersToRequestedWorkspace() {
        WikiKnowledgeBaseEntity workspaceOneKb = new WikiKnowledgeBaseEntity();
        workspaceOneKb.setId(1L);
        workspaceOneKb.setWorkspaceId(1L);
        WikiKnowledgeBaseEntity workspaceTwoKb = new WikiKnowledgeBaseEntity();
        workspaceTwoKb.setId(2L);
        workspaceTwoKb.setWorkspaceId(2L);
        when(kbService.listByAgentId(10L)).thenReturn(List.of(workspaceOneKb, workspaceTwoKb));
        when(pageService.countByKbId(2L)).thenReturn(3);

        var response = controller.listKBsByAgent(10L, 2L);

        assertEquals(1, response.getData().size());
        assertEquals(2L, response.getData().get(0).getWorkspaceId());
        assertEquals(3, response.getData().get(0).getPageCount());
    }
}
