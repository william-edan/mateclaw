package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 单测：注册种子化后整理 dashscope-default 下的默认模型——只启用并默认
 * deepseek-v3.2(chat) + text-embedding-v3(embedding)，其余禁用。
 */
class ModelConfigServiceRegistrationDefaultsTest {

    private ModelConfigMapper mapper;
    private ModelConfigService service;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                ModelConfigEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ModelConfigMapper.class);
        service = new ModelConfigService(mapper, mock(ApplicationEventPublisher.class),
                mock(ModelCapabilityService.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsOnlyDeepseekChatAndEmbeddingEnabledAndDefault() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                model("deepseek-v3.2", "chat", false, false),     // 目标 chat → 应启用+默认
                model("qwen-plus", "chat", true, true),           // 原默认 chat → 应禁用+清默认
                model("qwen-max", "chat", true, false),           // 其它 chat → 应禁用
                model("text-embedding-v3", "embedding", true, true),  // 目标向量 → 应启用+默认
                model("text-embedding-v2", "embedding", true, false))); // 其它向量 → 应禁用

        service.applyRegistrationDefaultModels(20L);

        org.mockito.ArgumentCaptor<ModelConfigEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(mapper, times(5)).updateById(captor.capture());
        List<ModelConfigEntity> saved = captor.getAllValues();

        ModelConfigEntity chat = byName(saved, "deepseek-v3.2");
        assertTrue(chat.getEnabled(), "deepseek-v3.2 应启用");
        assertTrue(chat.getIsDefault(), "deepseek-v3.2 应设为默认 chat");

        ModelConfigEntity emb = byName(saved, "text-embedding-v3");
        assertTrue(emb.getEnabled(), "text-embedding-v3 应启用");
        assertTrue(emb.getIsDefault(), "text-embedding-v3 应设为默认 embedding");

        for (String other : List.of("qwen-plus", "qwen-max", "text-embedding-v2")) {
            ModelConfigEntity m = byName(saved, other);
            assertFalse(m.getEnabled(), other + " 应被禁用");
            assertFalse(m.getIsDefault(), other + " 应清除默认标记");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsWhenChatTargetAbsentToAvoidNoDefault() {
        // dashscope-default 下没有 deepseek-v3.2（数据异常）→ 不整理，保留原默认，避免无默认 chat
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                model("qwen-plus", "chat", true, true),
                model("text-embedding-v3", "embedding", true, true)));

        service.applyRegistrationDefaultModels(20L);

        verify(mapper, never()).updateById(any(ModelConfigEntity.class));
    }

    @Test
    void defaultWorkspaceIsExempt() {
        service.applyRegistrationDefaultModels(ModelWorkspaceResolver.DEFAULT_WORKSPACE_ID);
        verifyNoInteractions(mapper);
    }

    private static ModelConfigEntity model(String name, String type, boolean enabled, boolean isDefault) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setWorkspaceId(20L);
        m.setProvider("dashscope-default");
        m.setModelName(name);
        m.setName(name);
        m.setModelType(type);
        m.setEnabled(enabled);
        m.setIsDefault(isDefault);
        m.setDeleted(0);
        return m;
    }

    private static ModelConfigEntity byName(List<ModelConfigEntity> list, String modelName) {
        return list.stream().filter(m -> modelName.equals(m.getModelName())).findFirst().orElseThrow();
    }
}
