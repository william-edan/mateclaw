package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.config.DefaultProviderKeyProperties;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.failover.ProviderInitProbe;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.model.ProviderInfoDTO;
import vip.mate.llm.model.ProviderTokenQuotaDTO;
import vip.mate.llm.model.ProviderConfigRequest;
import vip.mate.llm.repository.ModelProviderMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelProviderServiceWorkspaceIsolationTest {

    private ModelProviderMapper providerMapper;
    private ModelConfigService modelConfigService;
    private ApplicationEventPublisher eventPublisher;
    private ProviderTokenQuotaService quotaService;
    private ModelProviderService service;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                ModelProviderEntity.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        providerMapper = mock(ModelProviderMapper.class);
        modelConfigService = mock(ModelConfigService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        quotaService = mock(ProviderTokenQuotaService.class);
        ObjectProvider<ClaudeCodeOAuthService> claudeCodeOAuthProvider = mock(ObjectProvider.class);
        when(claudeCodeOAuthProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<ProviderInitProbe> initProbeProvider = mock(ObjectProvider.class);
        when(initProbeProvider.getIfAvailable()).thenReturn(null);

        service = new ModelProviderService(
                providerMapper,
                modelConfigService,
                eventPublisher,
                claudeCodeOAuthProvider,
                new AvailableProviderPool(),
                new ProviderHealthTracker(new ProviderHealthProperties()),
                defaultProviderKeys(),
                quotaService,
                initProbeProvider);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void listProvidersFiltersByCurrentWorkspace() {
        withWorkspace(20L);
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(java.util.List.of(provider("openai", 20L, "sk-ws20")));
        when(modelConfigService.listModels()).thenReturn(java.util.List.of());

        service.listProviders();

        LambdaQueryWrapper<ModelProviderEntity> wrapper = capturedProviderQuery();
        assertTrue(wrapper.getSqlSegment().contains("workspace_id"),
                "provider list query must be scoped by workspace_id, actual=" + wrapper.getSqlSegment()
                        + ", params=" + wrapper.getParamNameValuePairs());
    }

    @Test
    void updateProviderConfigSelectsProviderInsideCurrentWorkspace() {
        withWorkspace(20L);
        when(providerMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(provider("openai", 20L, "sk-old"));
        when(modelConfigService.listModelsByProvider("openai")).thenReturn(java.util.List.of());

        ProviderConfigRequest request = new ProviderConfigRequest();
        request.setApiKey("sk-new");
        request.setBaseUrl("https://api.example.com/v1");
        request.setProtocol("openai-compatible");

        service.updateProviderConfig("openai", request);

        verify(providerMapper).selectOne(any(LambdaQueryWrapper.class));
        verify(providerMapper).updateById(any(ModelProviderEntity.class));
    }

    @Test
    void seedWorkspaceModelsInjectsConfiguredDefaultKeysForNewRegistrations() {
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        provider("dashscope", 1L, ""),
                        provider("deepseek", 1L, ""),
                        provider("openai", 1L, "sk-template")));

        service.seedWorkspaceModels(20L);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ModelProviderEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ModelProviderEntity.class);
        verify(providerMapper, times(3)).insert(captor.capture());
        List<ModelProviderEntity> copies = captor.getAllValues();

        ModelProviderEntity dashscope = copyByProvider(copies, "dashscope");
        assertEquals(20L, dashscope.getWorkspaceId());
        assertEquals("sk-test-dashscope-default", dashscope.getApiKey());
        assertTrue(dashscope.getEnabled());

        ModelProviderEntity deepseek = copyByProvider(copies, "deepseek");
        assertEquals(20L, deepseek.getWorkspaceId());
        assertEquals("sk-test-deepseek-default", deepseek.getApiKey());
        assertTrue(deepseek.getEnabled());

        ModelProviderEntity openai = copyByProvider(copies, "openai");
        assertEquals("", openai.getApiKey());
        verify(quotaService).ensureDefaultQuotas(20L);
    }

    @Test
    void seedWorkspaceModelsPublishesModelConfigChangedEventToTriggerProbe() {
        // First selectList = "does target workspace already have providers?" (empty → proceed);
        // second = template providers from the default workspace.
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(provider("dashscope", 1L, "")));

        service.seedWorkspaceModels(20L);

        // Without this event the freshly-seeded (enabled + keyed) providers are never
        // handed to ProviderInitProbe, so they stay Liveness.UNPROBED and the cloud-model
        // card shows "检测中" forever until an app restart.
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }

    @Test
    void seedWorkspaceModelsSkipsEventWhenWorkspaceAlreadySeeded() {
        // Idempotent guard: target workspace already has providers → no copy, no event.
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(provider("dashscope", 20L, "sk-existing")));

        service.seedWorkspaceModels(20L);

        verify(providerMapper, never()).insert(any(ModelProviderEntity.class));
        verify(eventPublisher, never()).publishEvent(any(ModelConfigChangedEvent.class));
    }

    @Test
    void listProvidersIncludesQuotaFieldsForManagedProviders() {
        withWorkspace(20L);
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(provider("dashscope", 20L, "sk-test")));
        when(modelConfigService.listModels()).thenReturn(List.of());
        when(quotaService.getQuota(20L, "dashscope"))
                .thenReturn(new ProviderTokenQuotaDTO(2_000_000L, 125L, 1_999_875L, false));

        ProviderInfoDTO dto = service.listProviders().get(0);

        assertEquals(2_000_000L, dto.getQuotaLimitTokens());
        assertEquals(125L, dto.getQuotaUsedTokens());
        assertEquals(1_999_875L, dto.getQuotaRemainingTokens());
        assertEquals(false, dto.getQuotaExhausted());
    }

    private LambdaQueryWrapper<ModelProviderEntity> capturedProviderQuery() {
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<LambdaQueryWrapper<ModelProviderEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(providerMapper).selectList(captor.capture());
        return captor.getValue();
    }

    private static void withWorkspace(Long workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", workspaceId.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ModelProviderEntity provider(String id, Long workspaceId, String apiKey) {
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId(id);
        provider.setWorkspaceId(workspaceId);
        provider.setName(id);
        provider.setApiKey(apiKey);
        provider.setRequireApiKey(true);
        provider.setEnabled(true);
        provider.setIsLocal(false);
        provider.setIsCustom(false);
        return provider;
    }

    private static DefaultProviderKeyProperties defaultProviderKeys() {
        DefaultProviderKeyProperties properties = new DefaultProviderKeyProperties();
        properties.setDashscope("sk-test-dashscope-default");
        properties.setDeepseek("sk-test-deepseek-default");
        return properties;
    }

    private static ModelProviderEntity copyByProvider(List<ModelProviderEntity> copies, String providerId) {
        return copies.stream()
                .filter(copy -> providerId.equals(copy.getProviderId()))
                .findFirst()
                .orElseThrow();
    }
}
