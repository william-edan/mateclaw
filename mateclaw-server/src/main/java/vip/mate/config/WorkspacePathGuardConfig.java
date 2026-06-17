package vip.mate.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import vip.mate.tool.guard.WorkspacePathGuard;

/**
 * Wires the {@code mateclaw.workspace.path-guard-fail-closed} property (default
 * false) into the static {@link WorkspacePathGuard}. OFF by default keeps the
 * historical fail-open behaviour; enable only after WorkspaceBasePathBackfillRunner
 * has populated every workspace's basePath (else null-basePath workspaces lose all
 * file/shell tools).
 */
@Slf4j
@Configuration
public class WorkspacePathGuardConfig {

    public WorkspacePathGuardConfig(
            @Value("${mateclaw.workspace.path-guard-fail-closed:false}") boolean failClosed) {
        WorkspacePathGuard.setFailClosed(failClosed);
        if (failClosed) {
            log.info("[WorkspacePathGuard] fail-closed ENABLED — workspaces without a basePath "
                    + "will have file/shell tools denied");
        }
    }
}
