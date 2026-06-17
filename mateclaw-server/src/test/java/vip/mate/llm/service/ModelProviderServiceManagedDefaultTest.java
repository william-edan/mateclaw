package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.config.DefaultProviderKeyProperties;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.failover.ProviderInitProbe;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.model.ProviderConfigRequest;
import vip.mate.llm.model.ProviderInfoDTO;
import vip.mate.llm.repository.ModelProviderMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelProviderServiceManagedDefaultTest {

    private ModelProviderMapper providerMapper;
    private ModelConfigService modelConfigService;
    private ApplicationEventPublisher eventPublisher;
    private AvailableProviderPool pool;
    private ProviderHealthTracker healthTracker;
    private ProviderInitProbe initProbe;
    private DefaultProviderKeyProperties keyProps;
    private ModelProviderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        providerMapper = mock(ModelProviderMapper.class);
        modelConfigService = mock(ModelConfigService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        ObjectProvider<ClaudeCodeOAuthService> claudeProvider = mock(ObjectProvider.class);
        when(claudeProvider.getIfAvailable()).thenReturn(null);
        pool = new AvailableProviderPool();
        ProviderHealthProperties props = new ProviderHealthProperties();
        props.setFailureThreshold(1);
        healthTracker = new ProviderHealthTracker(props);
        initProbe = mock(ProviderInitProbe.class);
        ObjectProvider<ProviderInitProbe> initProbeProvider = mock(ObjectProvider.class);
        when(initProbeProvider.getIfAvailable()).thenReturn(initProbe);
        when(initProbe.hasBeenProbed(any())).thenReturn(true);
        keyProps = new DefaultProviderKeyProperties();
        keyProps.setDashscope("sk-platform-dash");
        keyProps.setDeepseek("sk-platform-deep");
        service = new ModelProviderService(providerMapper, modelConfigService, eventPublisher,
                claudeProvider, pool, healthTracker, keyProps,
                mock(ProviderTokenQuotaService.class), initProbeProvider);
    }

    private ModelProviderEntity provider(String id) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(id);
        p.setIsLocal(false);
        p.setIsCustom(false);
        p.setRequireApiKey(true);
        p.setEnabled(true);
        p.setApiKey("sk-real-key-1234567890");
        return p;
    }

    @Test
    void managedDefaultProviderIsFlaggedManagedKey() {
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(provider("dashscope-default")));
        when(modelConfigService.listModels()).thenReturn(List.of());
        ProviderInfoDTO dto = service.listProviders().get(0);
        assertEquals(Boolean.TRUE, dto.getManagedKey());
    }

    @Test
    void plainProviderIsNotManagedKey() {
        when(providerMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(provider("dashscope")));
        when(modelConfigService.listModels()).thenReturn(List.of());
        ProviderInfoDTO dto = service.listProviders().get(0);
        assertEquals(Boolean.FALSE, dto.getManagedKey());
    }

    @Test
    void updateProviderConfigIgnoresKeyAndBaseUrlForManaged() {
        ModelProviderEntity managed = provider("deepseek-default");
        managed.setApiKey("sk-platform-deep");
        managed.setBaseUrl("https://api.deepseek.com");
        when(providerMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(managed);
        when(modelConfigService.listModelsByProvider(any())).thenReturn(List.of());

        ProviderConfigRequest req = new ProviderConfigRequest();
        req.setApiKey("sk-HACKED");
        req.setBaseUrl("https://evil.example.com");
        req.setProtocol("openai-compatible");

        service.updateProviderConfig("deepseek-default", req);

        ArgumentCaptor<ModelProviderEntity> captor = ArgumentCaptor.forClass(ModelProviderEntity.class);
        verify(providerMapper).updateById(captor.capture());
        assertEquals("sk-platform-deep", captor.getValue().getApiKey(), "受管 provider 的 key 不可被覆盖");
        assertEquals("https://api.deepseek.com", captor.getValue().getBaseUrl(), "受管 provider 的 baseUrl 不可被覆盖");
    }

    @Test
    void updateProviderConfigStillWritesKeyForPlainProvider() {
        ModelProviderEntity plain = provider("deepseek");
        plain.setApiKey("");
        when(providerMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plain);
        when(modelConfigService.listModelsByProvider(any())).thenReturn(List.of());

        ProviderConfigRequest req = new ProviderConfigRequest();
        req.setApiKey("sk-user-own-key-1234");
        req.setBaseUrl("https://api.deepseek.com");
        req.setProtocol("openai-compatible");

        service.updateProviderConfig("deepseek", req);

        ArgumentCaptor<ModelProviderEntity> captor = ArgumentCaptor.forClass(ModelProviderEntity.class);
        verify(providerMapper).updateById(captor.capture());
        assertEquals("sk-user-own-key-1234", captor.getValue().getApiKey());
    }
}
