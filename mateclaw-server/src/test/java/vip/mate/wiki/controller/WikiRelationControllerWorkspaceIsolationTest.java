package vip.mate.wiki.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiProcessingJobMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiEmbeddingService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiRelationService;
import vip.mate.workspace.core.WorkspaceContextHolder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * pagesByRawId / pagesByChunkId took a raw/chunk id with no kbId and no guard —
 * any workspace could read another's pages by guessed id. The fix resolves
 * raw/chunk → owning KB and reuses the fail-closed verifyKbWorkspace (NOT the
 * sub-entity's nullable workspace_id column, which would fail-open).
 */
class WikiRelationControllerWorkspaceIsolationTest {

    private final WikiKnowledgeBaseMapper kbMapper = mock(WikiKnowledgeBaseMapper.class);
    private final WikiKnowledgeBaseService kbService = new WikiKnowledgeBaseService(
            kbMapper,
            mock(WikiRawMaterialMapper.class),
            mock(WikiPageMapper.class),
            mock(WikiChunkMapper.class),
            mock(WikiPageCitationMapper.class),
            mock(WikiProcessingJobMapper.class));
    private final WikiRawMaterialMapper rawMaterialMapper = mock(WikiRawMaterialMapper.class);
    private final WikiChunkMapper chunkMapper = mock(WikiChunkMapper.class);

    private final WikiRelationController controller = new WikiRelationController(
            mock(WikiRelationService.class),
            mock(vip.mate.wiki.job.WikiProcessingJobService.class),
            mock(WikiProcessingJobMapper.class),
            mock(WikiPageService.class),
            mock(WikiPageCitationMapper.class),
            mock(HybridRetriever.class),
            mock(ApplicationEventPublisher.class),
            new ObjectMapper(),
            mock(WikiEmbeddingService.class),
            kbService,
            rawMaterialMapper,
            chunkMapper);

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    private WikiKnowledgeBaseEntity kb(long id, Long workspaceId) {
        WikiKnowledgeBaseEntity e = new WikiKnowledgeBaseEntity();
        e.setId(id);
        e.setWorkspaceId(workspaceId);
        return e;
    }

    @Test
    void pagesByRawIdRejectsCrossWorkspace() {
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setId(11L);
        raw.setKbId(5L);
        when(rawMaterialMapper.selectById(11L)).thenReturn(raw);
        when(kbMapper.selectById(5L)).thenReturn(kb(5L, 2L)); // KB owned by workspace 2
        WorkspaceContextHolder.set(1L);

        assertThrows(MateClawException.class, () -> controller.pagesByRawId(11L),
                "raw of a KB owned by workspace 2 must not be readable from workspace 1");
    }

    @Test
    void pagesByChunkIdRejectsCrossWorkspace() {
        WikiChunkEntity chunk = new WikiChunkEntity();
        chunk.setId(22L);
        chunk.setKbId(5L);
        when(chunkMapper.selectById(22L)).thenReturn(chunk);
        when(kbMapper.selectById(5L)).thenReturn(kb(5L, 2L));
        WorkspaceContextHolder.set(1L);

        assertThrows(MateClawException.class, () -> controller.pagesByChunkId(22L),
                "chunk of a KB owned by workspace 2 must not be readable from workspace 1");
    }
}
