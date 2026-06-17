package vip.mate.workspace.core.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.workspace.core.service.WorkspaceService;

/**
 * One-shot startup backfill: gives every existing workspace a basePath sandbox
 * directory (created before basePath seeding existed → null → unbounded file tool).
 *
 * <p>{@code @Order(6)} so it runs after {@code WorkspaceSchemaMigration} ({@code @Order(5)})
 * — the default workspace row must exist first. Idempotent and non-fatal: in steady
 * state it is a no-op. This must run (and be verified to leave no null basePath)
 * before {@code mateclaw.workspace.path-guard-fail-closed} is turned on.
 */
@Slf4j
@Component
@Order(6)
@RequiredArgsConstructor
public class WorkspaceBasePathBackfillRunner implements ApplicationRunner {

    private final WorkspaceService workspaceService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int fixed = workspaceService.backfillMissingBasePaths();
            if (fixed > 0) {
                log.info("[WorkspaceBasePathBackfill] backfilled basePath for {} existing workspace(s)", fixed);
            }
        } catch (Exception e) {
            // Non-fatal: never block startup on a backfill hiccup.
            log.warn("[WorkspaceBasePathBackfill] backfill failed: {}", e.getMessage());
        }
    }
}
