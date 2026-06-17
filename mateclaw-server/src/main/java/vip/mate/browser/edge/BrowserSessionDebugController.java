package vip.mate.browser.edge;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.edge.session.BrowserSessionView;
import vip.mate.common.result.R;

import java.util.List;

/**
 * Read-only browser edge session endpoint. Admin-only — protected by
 * {@code SecurityConfig}. The desktop client reads this to show whether the
 * Native-Messaging browser extension is connected: it cannot ping the
 * extension from inside Electron, so a non-empty list IS the connected signal.
 * Phase 1: in-memory view only; Phase 4 will switch to DB-backed queries.
 */
@RestController
@RequestMapping("/api/v1/browser/sessions")
@RequiredArgsConstructor
public class BrowserSessionDebugController {

    private final BrowserSessionRegistry registry;

    @GetMapping
    public R<List<BrowserSessionView>> list() {
        return R.ok(registry.snapshot());
    }
}
