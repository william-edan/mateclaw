package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.exception.MateClawException;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.repository.ModelConfigMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the per-workspace embedding-default contract introduced by the
 * "registered user sees no embedding model" fix (2026-06-17).
 *
 * <p>Before the fix, the embedding default was a single global
 * {@code mate_system_setting} row; this aligns it with the chat model's
 * per-workspace {@code is_default} flag so each workspace owns its own
 * default and a workspace admin can manage it.
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigServiceEmbeddingDefaultTest {

    @Mock private ModelConfigMapper modelConfigMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ModelCapabilityService modelCapabilityService;
    @Mock private ModelProviderService modelProviderService;

    @InjectMocks private ModelConfigService service;

    private static final long WS = 2067091758814740481L;

    @AfterEach
    void clearWorkspace() {
        ModelWorkspaceResolver.clear();
    }

    private ModelConfigEntity embeddingRow(long id, boolean isDefault, boolean enabled) {
        ModelConfigEntity e = new ModelConfigEntity();
        e.setId(id);
        e.setWorkspaceId(WS);
        e.setProvider("dashscope");
        e.setModelName("text-embedding-v3");
        e.setModelType("embedding");
        e.setEnabled(enabled);
        e.setIsDefault(isDefault);
        return e;
    }

    @Test
    @DisplayName("getDefaultEmbeddingModel: 返回当前工作区 is_default 的 embedding 行")
    void getDefaultEmbeddingModel_returnsWorkspaceDefault() {
        ModelWorkspaceResolver.setCurrentWorkspaceId(WS);
        ModelConfigEntity def = embeddingRow(900L, true, true);
        when(modelConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(def);

        ModelConfigEntity result = service.getDefaultEmbeddingModel();

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(900L);
        assertThat(result.getModelType()).isEqualTo("embedding");
    }

    @Test
    @DisplayName("setDefaultEmbeddingModel: 把目标 embedding 行标为默认并发布变更事件")
    void setDefaultEmbeddingModel_marksTargetDefault() {
        ModelWorkspaceResolver.setCurrentWorkspaceId(WS);
        ModelConfigEntity target = embeddingRow(901L, false, true);
        when(modelConfigMapper.selectById(901L)).thenReturn(target);
        // clearDefaultFlag("embedding") 的查询：当前没有其它默认
        when(modelConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());

        service.setDefaultEmbeddingModel(901L);

        ArgumentCaptor<ModelConfigEntity> captor = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(modelConfigMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(e -> e.getId().equals(901L) && Boolean.TRUE.equals(e.getIsDefault()));
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }

    @Test
    @DisplayName("setDefaultEmbeddingModel: 非 embedding 类型应抛异常")
    void setDefaultEmbeddingModel_rejectsNonEmbedding() {
        ModelWorkspaceResolver.setCurrentWorkspaceId(WS);
        ModelConfigEntity chat = new ModelConfigEntity();
        chat.setId(902L);
        chat.setWorkspaceId(WS);
        chat.setModelType("chat");
        chat.setEnabled(true);
        when(modelConfigMapper.selectById(902L)).thenReturn(chat);

        assertThatThrownBy(() -> service.setDefaultEmbeddingModel(902L))
                .isInstanceOf(MateClawException.class);
    }
}
