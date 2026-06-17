package vip.mate.skill.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.workspace.core.WorkspaceContextHolder;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Gated per-workspace skill directory layout. With the flag on, non-default
 * workspaces resolve under an isolated {@code {root}/{wsId}} subtree while the
 * default workspace keeps the legacy flat root (zero migration for existing
 * skills). With the flag off (default), every workspace uses the legacy flat root.
 */
class SkillWorkspaceManagerWorkspaceDirTest {

    @TempDir
    Path tempRoot;

    private SkillWorkspaceManager manager;

    @BeforeEach
    void setUp() {
        SkillWorkspaceProperties props = new SkillWorkspaceProperties();
        props.setRoot(tempRoot.toString());
        manager = new SkillWorkspaceManager(props, mock(ApplicationEventPublisher.class));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void perWorkspaceDirIsolatesNonDefaultWorkspaces() {
        ReflectionTestUtils.setField(manager, "perWorkspaceDir", true);

        WorkspaceContextHolder.set(1L);
        assertThat(manager.getWorkspaceRoot()).isEqualTo(tempRoot); // default ws = legacy flat root

        WorkspaceContextHolder.set(2L);
        assertThat(manager.getWorkspaceRoot()).isEqualTo(tempRoot.resolve("2"));
        assertThat(manager.resolveConventionPath("my-skill"))
                .isEqualTo(tempRoot.resolve("2").resolve("my-skill"));
    }

    @Test
    void legacyFlatLayoutWhenDisabled() {
        // perWorkspaceDir defaults false → every workspace uses the flat root.
        WorkspaceContextHolder.set(2L);
        assertThat(manager.getWorkspaceRoot()).isEqualTo(tempRoot);
    }
}
