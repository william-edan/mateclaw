package vip.mate.wiki.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiProcessingJobMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;
import vip.mate.workspace.core.WorkspaceContextHolder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wiki sub-entities (chunk/page/relation/raw) carry no workspace_id; the KB is the
 * authorization source for every {@code /kb/{kbId}/**} endpoint. Pins the shared
 * fail-closed {@code verifyKbWorkspace} guard (cross-ws denied, missing denied,
 * null kbId denied, workspace-less public KB allowed).
 */
class WikiKbWorkspaceVerifyTest {

    private final WikiKnowledgeBaseMapper kbMapper = mock(WikiKnowledgeBaseMapper.class);
    private final WikiKnowledgeBaseService service = new WikiKnowledgeBaseService(
            kbMapper,
            mock(WikiRawMaterialMapper.class),
            mock(WikiPageMapper.class),
            mock(WikiChunkMapper.class),
            mock(WikiPageCitationMapper.class),
            mock(WikiProcessingJobMapper.class));

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
    void rejectsCrossWorkspaceKb() {
        when(kbMapper.selectById(5L)).thenReturn(kb(5L, 2L));
        WorkspaceContextHolder.set(1L);
        assertThrows(MateClawException.class, () -> service.verifyKbWorkspace(5L));
    }

    @Test
    void allowsSameWorkspaceKb() {
        when(kbMapper.selectById(5L)).thenReturn(kb(5L, 1L));
        WorkspaceContextHolder.set(1L);
        assertDoesNotThrow(() -> service.verifyKbWorkspace(5L));
    }

    @Test
    void rejectsMissingKb() {
        when(kbMapper.selectById(9L)).thenReturn(null);
        WorkspaceContextHolder.set(1L);
        assertThrows(MateClawException.class, () -> service.verifyKbWorkspace(9L));
    }

    @Test
    void rejectsNullKbId() {
        WorkspaceContextHolder.set(1L);
        assertThrows(MateClawException.class, () -> service.verifyKbWorkspace(null));
    }

    @Test
    void allowsWorkspacelessPublicKb() {
        when(kbMapper.selectById(7L)).thenReturn(kb(7L, null));
        WorkspaceContextHolder.set(1L);
        assertDoesNotThrow(() -> service.verifyKbWorkspace(7L));
    }
}
