package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelConfigServiceWorkspaceIsolationTest {

    private ModelConfigMapper modelConfigMapper;
    private ModelConfigService service;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                ModelConfigEntity.class);
    }

    @BeforeEach
    void setUp() {
        modelConfigMapper = mock(ModelConfigMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ModelConfigService(modelConfigMapper, eventPublisher, mock(ModelCapabilityService.class));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void listEnabledModelsFiltersByCurrentWorkspace() {
        withWorkspace(20L);
        when(modelConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());

        service.listEnabledModels();

        LambdaQueryWrapper<ModelConfigEntity> wrapper = capturedModelQuery();
        assertTrue(wrapper.getSqlSegment().contains("workspace_id"),
                "enabled model query must be scoped by workspace_id, actual=" + wrapper.getSqlSegment()
                        + ", params=" + wrapper.getParamNameValuePairs());
    }

    @Test
    void addModelToProviderStampsCurrentWorkspace() {
        withWorkspace(20L);
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.addModelToProvider("openai", "gpt-4o", "GPT-4o", false);

        org.mockito.ArgumentCaptor<ModelConfigEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigMapper).insert(captor.capture());
        assertTrue(Long.valueOf(20L).equals(captor.getValue().getWorkspaceId()),
                "inserted model row must inherit the current workspace");
    }

    /**
     * Registration auto-provisioning: when a user registers, {@code seedWorkspaceModels}
     * calls {@code copyModelsToWorkspace(DEFAULT_WORKSPACE_ID, newWorkspaceId)}, which must
     * carry the two DashScope embedding rows that {@code V14__embedding_model_config.sql}
     * seeds into the template workspace — with {@code model_type='embedding'} preserved —
     * so a fresh user's wiki can build vectors against text-embedding-v3 / v2 out of the box.
     *
     * <p>The sibling {@code ModelProviderServiceWorkspaceIsolationTest} mocks
     * {@code ModelConfigService}, so this copy step was previously unasserted anywhere.
     */
    @Test
    @DisplayName("registration copy: DashScope embedding models (text-embedding-v3/v2) reach the new workspace with model_type preserved")
    void copyModelsToWorkspaceCopiesDashScopeEmbeddingModels() {
        long targetWorkspaceId = 20L;
        // First selectList = "does the target already have models?" guard (empty for a fresh
        // registration). Second selectList = templates from the default workspace, mirroring
        // the rows V14__embedding_model_config.sql seeds into workspace 1.
        when(modelConfigMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        templateModel("dashscope", "qwen-plus", "Qwen Plus", "chat"),
                        templateModel("dashscope", "text-embedding-v3", "Text Embedding v3", "embedding"),
                        templateModel("dashscope", "text-embedding-v2", "Text Embedding v2", "embedding")));

        service.copyModelsToWorkspace(ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID, targetWorkspaceId);

        ArgumentCaptor<ModelConfigEntity> captor = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigMapper, times(3)).insert(captor.capture());
        List<ModelConfigEntity> copies = captor.getAllValues();

        ModelConfigEntity v3 = copyByModelName(copies, "text-embedding-v3");
        assertEquals(targetWorkspaceId, v3.getWorkspaceId(), "v3 copy must land in the new workspace");
        assertEquals("embedding", v3.getModelType(), "v3 copy must keep model_type=embedding");
        assertEquals("dashscope", v3.getProvider(), "v3 copy must stay on the dashscope provider");

        ModelConfigEntity v2 = copyByModelName(copies, "text-embedding-v2");
        assertEquals(targetWorkspaceId, v2.getWorkspaceId(), "v2 copy must land in the new workspace");
        assertEquals("embedding", v2.getModelType(), "v2 copy must keep model_type=embedding");
        assertEquals("dashscope", v2.getProvider(), "v2 copy must stay on the dashscope provider");
    }

    private static ModelConfigEntity templateModel(String provider, String modelName, String name, String modelType) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setWorkspaceId(ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID);
        model.setProvider(provider);
        model.setModelName(modelName);
        model.setName(name);
        model.setModelType(modelType);
        model.setBuiltin(true);
        model.setEnabled(true);
        model.setDeleted(0);
        return model;
    }

    private static ModelConfigEntity copyByModelName(List<ModelConfigEntity> copies, String modelName) {
        return copies.stream()
                .filter(copy -> modelName.equals(copy.getModelName()))
                .findFirst()
                .orElseThrow();
    }

    private LambdaQueryWrapper<ModelConfigEntity> capturedModelQuery() {
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<LambdaQueryWrapper<ModelConfigEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(modelConfigMapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static void withWorkspace(Long workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", workspaceId.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
