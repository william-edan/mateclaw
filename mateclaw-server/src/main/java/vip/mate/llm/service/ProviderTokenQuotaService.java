package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ProviderTokenQuotaDTO;
import vip.mate.llm.model.ProviderTokenQuotaEntity;
import vip.mate.llm.repository.ProviderTokenQuotaMapper;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProviderTokenQuotaService {

    public static final String QUOTA_EXHAUSTED_MESSAGE = "额度已用完，请在 设置-模型管理 中充值或切换模型";

    private static final Map<String, Long> DEFAULT_LIMITS = java.util.LinkedHashMap.newLinkedHashMap(2);

    static {
        DEFAULT_LIMITS.put("dashscope-default", 2_000_000L);
        DEFAULT_LIMITS.put("deepseek-default", 3_000_000L);
    }

    private final ProviderTokenQuotaMapper mapper;

    public boolean isManagedProvider(String providerId) {
        return DEFAULT_LIMITS.containsKey(providerId);
    }

    /** 默认（管理员/平台模板）工作区豁免配额限制。 */
    private boolean isDefaultWorkspace(Long workspaceId) {
        return workspaceId != null && workspaceId == ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID;
    }

    @Transactional
    public void ensureDefaultQuotas(Long workspaceId) {
        if (workspaceId == null || isDefaultWorkspace(workspaceId)) {
            return;
        }
        for (Map.Entry<String, Long> entry : DEFAULT_LIMITS.entrySet()) {
            ensureQuota(workspaceId, entry.getKey(), entry.getValue());
        }
    }

    public ProviderTokenQuotaDTO getQuota(Long workspaceId, String providerId) {
        if (workspaceId == null || isDefaultWorkspace(workspaceId) || !isManagedProvider(providerId)) {
            return null;
        }
        ProviderTokenQuotaEntity quota = findQuota(workspaceId, providerId);
        if (quota == null) {
            quota = ensureQuota(workspaceId, providerId, DEFAULT_LIMITS.get(providerId));
        }
        return ProviderTokenQuotaDTO.from(quota);
    }

    public void assertNotExhausted(Long workspaceId, String providerId) {
        ProviderTokenQuotaDTO quota = getQuota(workspaceId, providerId);
        if (quota != null && quota.exhausted()) {
            throw new MateClawException("err.llm.provider_quota_exhausted", 429, QUOTA_EXHAUSTED_MESSAGE);
        }
    }

    @Transactional
    public void recordUsage(Long workspaceId, String providerId, int promptTokens, int completionTokens) {
        if (workspaceId == null || isDefaultWorkspace(workspaceId) || !isManagedProvider(providerId)) {
            return;
        }
        long delta = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        if (delta <= 0) {
            return;
        }
        ProviderTokenQuotaEntity quota = findQuota(workspaceId, providerId);
        if (quota == null) {
            ensureQuota(workspaceId, providerId, DEFAULT_LIMITS.get(providerId));
        }
        mapper.incrementUsedTokens(workspaceId, providerId, delta);
    }

    private ProviderTokenQuotaEntity ensureQuota(Long workspaceId, String providerId, Long limitTokens) {
        ProviderTokenQuotaEntity existing = findQuota(workspaceId, providerId);
        if (existing != null) {
            return existing;
        }
        ProviderTokenQuotaEntity quota = new ProviderTokenQuotaEntity();
        quota.setWorkspaceId(workspaceId);
        quota.setProviderId(providerId);
        quota.setLimitTokens(limitTokens);
        quota.setUsedTokens(0L);
        mapper.insert(quota);
        return quota;
    }

    private ProviderTokenQuotaEntity findQuota(Long workspaceId, String providerId) {
        return mapper.selectOne(new LambdaQueryWrapper<ProviderTokenQuotaEntity>()
                .eq(ProviderTokenQuotaEntity::getWorkspaceId, workspaceId)
                .eq(ProviderTokenQuotaEntity::getProviderId, providerId)
                .last("LIMIT 1"));
    }
}
