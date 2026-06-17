package vip.mate.tool.guard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.tool.builtin.ToolExecutionContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * fail-closed mode: when a workspace has no basePath, the file/shell guard must
 * DENY instead of running unsandboxed. Default (fail-open) keeps the legacy
 * "no basePath → no restriction" behaviour so existing deployments are unaffected
 * until the backfill has run and the flag is flipped.
 */
class WorkspacePathGuardFailClosedTest {

    @AfterEach
    void reset() {
        WorkspacePathGuard.setFailClosed(false);
        ToolExecutionContext.clear();
    }

    @Test
    void failClosedDeniesFileAccessWhenNoBasePath() {
        WorkspacePathGuard.setFailClosed(true);
        // No ToolExecutionContext / ChatOrigin → resolveBasePath == null.
        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePathGuard.validatePath("/etc/passwd"));
    }

    @Test
    void failClosedDeniesShellWhenNoBasePath() {
        WorkspacePathGuard.setFailClosed(true);
        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePathGuard.validateShellCommand("cat /etc/passwd"));
    }

    @Test
    void failOpenByDefaultStillAllowsWhenNoBasePath() {
        WorkspacePathGuard.setFailClosed(false);
        assertDoesNotThrow(() -> WorkspacePathGuard.validatePath("/tmp/whatever"));
        assertDoesNotThrow(() -> WorkspacePathGuard.validateShellCommand("echo hi"));
    }
}
