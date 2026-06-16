package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.llm.embedding.EmbeddingModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.repository.SystemSettingMapper;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that embedding availability is gated on provider <em>liveness</em>
 * (init-probe passed), not merely on a non-placeholder key being present.
 * A provider whose key returns 401 is REMOVED from the pool, so the KB must be
 * reported as having no usable embedding model and ingestion must be blocked.
 */
class WikiEmbeddingAvailabilityTest {

    private static final Long KB_ID = 9L;

    private ModelConfigService modelConfigService;
    private ModelProviderService modelProviderService;
    private SystemSettingMapper systemSettingMapper;
    private WikiEmbeddingService service;

    @BeforeEach
    void setUp() {
        WikiChunkMapper chunkMapper = mock(WikiChunkMapper.class);
        WikiPageMapper pageMapper = mock(WikiPageMapper.class);
        WikiRawMaterialMapper rawMaterialMapper = mock(WikiRawMaterialMapper.class);
        WikiProperties properties = new WikiProperties();
        EmbeddingModelFactory factory = mock(EmbeddingModelFactory.class);
        modelConfigService = mock(ModelConfigService.class);
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        systemSettingMapper = mock(SystemSettingMapper.class);
        modelProviderService = mock(ModelProviderService.class);
        WikiEmbeddingInputBuilder inputBuilder = new WikiEmbeddingInputBuilder();

        // No KB-level binding and no system default → resolution falls through to
        // "first enabled embedding model", which is what these tests stub.
        when(kbService.getById(KB_ID)).thenReturn(null);
        when(systemSettingMapper.selectOne(any())).thenReturn(null);

        service = new WikiEmbeddingService(chunkMapper, pageMapper, rawMaterialMapper, properties, factory,
                modelConfigService, kbService, systemSettingMapper, modelProviderService, inputBuilder);
    }

    private ModelConfigEntity embeddingModel(String provider) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setEnabled(true);
        m.setModelType("embedding");
        m.setProvider(provider);
        m.setModelName("text-embedding-v3");
        return m;
    }

    @Test
    @DisplayName("provider not live (e.g. 401/removed) → no usable model, guard throws")
    void blocksWhenProviderNotLive() {
        when(modelConfigService.findFirstEnabledEmbedding()).thenReturn(embeddingModel("dashscope"));
        when(modelProviderService.isProviderLive("dashscope")).thenReturn(false);

        assertFalse(service.hasUsableEmbeddingModel(KB_ID),
                "a configured-but-not-live provider must not count as usable");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.requireUsableEmbeddingModel(KB_ID));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("no embedding model configured at all → guard throws")
    void blocksWhenNoEmbeddingModel() {
        when(modelConfigService.findFirstEnabledEmbedding()).thenReturn(null);

        assertFalse(service.hasUsableEmbeddingModel(KB_ID));
        assertThrows(MateClawException.class, () -> service.requireUsableEmbeddingModel(KB_ID));
    }

    @Test
    @DisplayName("provider live → usable, guard passes")
    void allowsWhenProviderLive() {
        when(modelConfigService.findFirstEnabledEmbedding()).thenReturn(embeddingModel("dashscope"));
        when(modelProviderService.isProviderLive("dashscope")).thenReturn(true);

        assertTrue(service.hasUsableEmbeddingModel(KB_ID));
        assertDoesNotThrow(() -> service.requireUsableEmbeddingModel(KB_ID));
    }

    @Test
    @DisplayName("system default id points at the template workspace → resolves to THIS workspace's copy by (provider, modelName)")
    void resolvesSystemDefaultAcrossWorkspaces() {
        SystemSettingEntity setting = new SystemSettingEntity();
        setting.setSettingKey(WikiEmbeddingService.SYSTEM_SETTING_DEFAULT_EMBEDDING_ID);
        setting.setSettingValue("1000001001");
        when(systemSettingMapper.selectOne(any())).thenReturn(setting);

        // The global id lives in the template workspace, so the workspace-guarded
        // getModel rejects it — exactly what bites a freshly-registered user.
        when(modelConfigService.getModel(1000001001L))
                .thenThrow(new MateClawException("err.llm.model_config_not_found", "not in current workspace"));
        // Cross-workspace deref yields the template row's identity...
        ModelConfigEntity reference = embeddingModel("dashscope");
        reference.setId(1000001001L);
        when(modelConfigService.findModelByIdAnyWorkspace(1000001001L)).thenReturn(reference);
        // ...which maps to this workspace's enabled copy under a fresh snowflake id.
        ModelConfigEntity localCopy = embeddingModel("dashscope");
        localCopy.setId(902000000000000001L);
        when(modelConfigService.findEnabledModel("dashscope", "text-embedding-v3")).thenReturn(localCopy);

        // Prove tier-2 stands on its own: tier-3 (first enabled) finds nothing.
        when(modelConfigService.findFirstEnabledEmbedding()).thenReturn(null);
        when(modelProviderService.isProviderLive("dashscope")).thenReturn(true);

        assertTrue(service.hasUsableEmbeddingModel(KB_ID),
                "global default id must resolve to the current workspace's equivalent embedding model");
    }

    @Test
    @DisplayName("system default id already in the current workspace → used directly, no cross-workspace lookup")
    void usesSystemDefaultDirectlyWhenInCurrentWorkspace() {
        SystemSettingEntity setting = new SystemSettingEntity();
        setting.setSettingKey(WikiEmbeddingService.SYSTEM_SETTING_DEFAULT_EMBEDDING_ID);
        setting.setSettingValue("555");
        when(systemSettingMapper.selectOne(any())).thenReturn(setting);

        ModelConfigEntity local = embeddingModel("dashscope");
        local.setId(555L);
        when(modelConfigService.getModel(555L)).thenReturn(local);
        when(modelProviderService.isProviderLive("dashscope")).thenReturn(true);

        assertTrue(service.hasUsableEmbeddingModel(KB_ID));
        verify(modelConfigService, never()).findModelByIdAnyWorkspace(any());
    }
}
