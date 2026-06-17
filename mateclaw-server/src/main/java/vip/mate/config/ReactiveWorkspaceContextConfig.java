package vip.mate.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import vip.mate.workspace.core.WorkspaceThreadLocalAccessor;

/**
 * Gated wiring for reactive workspace-context propagation (Part 5 §5.2).
 *
 * <p>{@code Hooks.enableAutomaticContextPropagation()} is a JVM-global Reactor
 * switch affecting every reactive flow, so it is OFF by default. Turning it on
 * (after off-request binding is solid and a regression pass is done) makes the
 * agent reactive path carry the real workspace across schedulers — which also
 * closes the §2.1 skill-secret reactive residual and unblocks per-workspace
 * skill directories.
 */
@Slf4j
@Configuration
public class ReactiveWorkspaceContextConfig {

    @Value("${mateclaw.tenant.reactive-context-propagation-enabled:false}")
    private boolean enabled;

    @PostConstruct
    public void init() {
        if (!enabled) {
            return;
        }
        ContextRegistry.getInstance().registerThreadLocalAccessor(new WorkspaceThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();
        log.info("[ReactiveWorkspaceContext] Automatic Reactor context propagation ENABLED — "
                + "workspace now flows across the agent reactive chain");
    }
}
