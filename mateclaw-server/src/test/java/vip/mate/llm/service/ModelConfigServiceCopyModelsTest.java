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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelConfigServiceCopyModelsTest {

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
    void copyOnlyIncludesAllowedProviders() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())                        // 目标工作区为空 → 继续
                .thenReturn(List.of(                          // 源模板
                        model("dashscope-default", "qwen-plus"),
                        model("deepseek-default", "deepseek-chat"),
                        model("dashscope", "qwen-max"),
                        model("openai", "gpt-4o")));

        service.copyModelsToWorkspace(1L, 20L, Set.of("dashscope-default", "deepseek-default"));

        org.mockito.ArgumentCaptor<ModelConfigEntity> captor =
                org.mockito.ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertEquals(Set.of("dashscope-default", "deepseek-default"),
                captor.getAllValues().stream().map(ModelConfigEntity::getProvider)
                        .collect(Collectors.toSet()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullAllowedSetCopiesEverything() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        model("dashscope-default", "qwen-plus"),
                        model("openai", "gpt-4o")));

        service.copyModelsToWorkspace(1L, 20L, null);

        verify(mapper, times(2)).insert(any(ModelConfigEntity.class));
    }

    private static ModelConfigEntity model(String provider, String modelName) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setProvider(provider);
        m.setModelName(modelName);
        m.setName(modelName);
        m.setWorkspaceId(1L);
        m.setModelType("chat");
        m.setEnabled(true);
        return m;
    }
}
