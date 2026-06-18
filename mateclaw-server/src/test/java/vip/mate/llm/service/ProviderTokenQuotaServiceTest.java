package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ProviderTokenQuotaEntity;
import vip.mate.llm.repository.ProviderTokenQuotaMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProviderTokenQuotaServiceTest {

    private ProviderTokenQuotaMapper mapper;
    private ProviderTokenQuotaService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ProviderTokenQuotaMapper.class);
        service = new ProviderTokenQuotaService(mapper);
    }

    @Test
    void ensureDefaultQuotasCreatesDefaultVersionRows() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.ensureDefaultQuotas(20L);

        var captor = org.mockito.ArgumentCaptor.forClass(ProviderTokenQuotaEntity.class);
        verify(mapper, times(2)).insert(captor.capture());

        ProviderTokenQuotaEntity dashscope = captor.getAllValues().get(0);
        assertEquals(20L, dashscope.getWorkspaceId());
        assertEquals("dashscope-default", dashscope.getProviderId());
        assertEquals(2_000_000L, dashscope.getLimitTokens());
        assertEquals(0L, dashscope.getUsedTokens());

        ProviderTokenQuotaEntity deepseek = captor.getAllValues().get(1);
        assertEquals("deepseek-default", deepseek.getProviderId());
        assertEquals(3_000_000L, deepseek.getLimitTokens());
    }

    @Test
    void assertNotExhaustedRejectsManagedDefaultProviderAtLimit() {
        ProviderTokenQuotaEntity quota = quota("dashscope-default", 2_000_000L, 2_000_000L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.assertNotExhausted(20L, "dashscope-default"));

        assertEquals("额度已用完，请在 设置-模型管理 中充值或切换模型", ex.getMessage());
        assertEquals(429, ex.getCode());
    }

    @Test
    void recordUsageAddsPromptAndCompletionTokens() {
        ProviderTokenQuotaEntity quota = quota("deepseek-default", 3_000_000L, 12L);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota);

        service.recordUsage(20L, "deepseek-default", 30, 40);

        verify(mapper).incrementUsedTokens(20L, "deepseek-default", 70L);
        verify(mapper, never()).updateById(any(ProviderTokenQuotaEntity.class));
    }

    @Test
    void unmanagedProvidersDoNothing() {
        // 第三方云端、以及现在已不再受管的原版 dashscope/deepseek 都不应触发任何 mapper 调用
        service.assertNotExhausted(20L, "openai");
        service.recordUsage(20L, "openai", 10, 20);
        service.assertNotExhausted(20L, "dashscope");
        service.recordUsage(20L, "deepseek", 10, 20);

        verifyNoInteractions(mapper);
    }

    @Test
    void defaultWorkspaceIsExemptFromQuota() {
        long admin = ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID; // 1L

        assertNull(service.getQuota(admin, "dashscope-default"),
                "管理员工作区不展示配额");
        // 即使没有任何 stub 也不抛、不查 mapper —— 说明被豁免，根本没走到 mapper
        service.assertNotExhausted(admin, "dashscope-default");
        service.recordUsage(admin, "dashscope-default", 1000, 2000);
        service.ensureDefaultQuotas(admin);

        verifyNoInteractions(mapper);
    }

    @Test
    void recordUsageLazilyCreatesDefaultVersionQuotaRow() {
        // 非默认工作区(老用户)首次命中默认版：findQuota 返回 null → 懒创建 2M 限额行后再累加
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.recordUsage(20L, "dashscope-default", 100, 200);

        var captor = org.mockito.ArgumentCaptor.forClass(ProviderTokenQuotaEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("dashscope-default", captor.getValue().getProviderId());
        assertEquals(2_000_000L, captor.getValue().getLimitTokens());
        verify(mapper).incrementUsedTokens(20L, "dashscope-default", 300L);
    }

    private static ProviderTokenQuotaEntity quota(String providerId, long limit, long used) {
        ProviderTokenQuotaEntity quota = new ProviderTokenQuotaEntity();
        quota.setWorkspaceId(20L);
        quota.setProviderId(providerId);
        quota.setLimitTokens(limit);
        quota.setUsedTokens(used);
        return quota;
    }
}
