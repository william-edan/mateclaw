package vip.mate.skill.secret;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.repository.SkillSecretMapper;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Agent tool path ({@code SkillScriptTool} / {@code ScriptSkillWrapperToolFactory})
 * decrypts a skill's secrets via {@link SkillSecretService#getDecrypted}. Without a
 * guard a workspace-A agent could decrypt workspace-B's credentials. These pin the
 * <em>enforce-when-bound</em> guard: enforced when a workspace is bound on
 * {@link WorkspaceContextHolder}, skipped (no false-block) when unbound, builtin allowed.
 */
class SkillSecretServiceWorkspaceIsolationTest {

    private SkillSecretMapper secretMapper;
    private SkillMapper skillMapper;
    private SkillSecretService service;

    @BeforeAll
    static void initMyBatisPlusCache() {
        Configuration configuration = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SkillSecretEntity.class);
    }

    @BeforeEach
    void setUp() {
        secretMapper = mock(SkillSecretMapper.class);
        skillMapper = mock(SkillMapper.class);
        service = new SkillSecretService(secretMapper, skillMapper);
        ReflectionTestUtils.setField(service, "encryptKey", "TestKey-1234567");
        when(secretMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    private SkillEntity skill(long id, Long workspaceId, boolean builtin) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setWorkspaceId(workspaceId);
        s.setBuiltin(builtin);
        return s;
    }

    @Test
    void deniesCrossWorkspaceSkillWhenContextBound() {
        when(skillMapper.selectById(50L)).thenReturn(skill(50L, 2L, false));
        WorkspaceContextHolder.set(1L);
        assertThrows(MateClawException.class, () -> service.getDecrypted(50L),
                "workspace 1 must not decrypt workspace 2's skill secret");
    }

    @Test
    void allowsSameWorkspaceSkillWhenBound() {
        when(skillMapper.selectById(50L)).thenReturn(skill(50L, 1L, false));
        WorkspaceContextHolder.set(1L);
        assertDoesNotThrow(() -> service.getDecrypted(50L));
    }

    @Test
    void allowsBuiltinSkill() {
        when(skillMapper.selectById(51L)).thenReturn(skill(51L, null, true));
        WorkspaceContextHolder.set(1L);
        assertDoesNotThrow(() -> service.getDecrypted(51L));
    }

    @Test
    void skipsCheckWhenContextUnbound() {
        // reactive agent path (until Reactor-context propagation lands): holder unbound
        // → must NOT false-block; the guard only enforces when a workspace is bound.
        when(skillMapper.selectById(50L)).thenReturn(skill(50L, 2L, false));
        assertDoesNotThrow(() -> service.getDecrypted(50L));
    }
}
