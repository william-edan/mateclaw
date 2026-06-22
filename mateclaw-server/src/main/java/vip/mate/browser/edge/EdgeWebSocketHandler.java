package vip.mate.browser.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.auth.EdgeAuthInterceptor;
import vip.mate.browser.edge.auth.EdgePrincipal;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;
import vip.mate.browser.orchestrator.ActionExecutionService;
import vip.mate.browser.orchestrator.domain.PageEvent;
import vip.mate.browser.orchestrator.screenshot.ScreenshotEdgeClient;
import vip.mate.browser.orchestrator.snapshot.SnapshotEdgeClient;
import vip.mate.browser.orchestrator.snapshot.PageSnapshotService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 Edge WebSocket handler.
 *
 * <p>Handles only protocol envelope kinds defined in edge-protocol.md §1:
 * hello, heartbeat, ping (and the corresponding acks/responses).
 *
 * <p>Unknown kinds are logged and silently dropped per the spec
 * (forward-compat). The only conditions that close the connection are
 * protocol-version mismatch (4400) and an unknown session_id on a
 * post-hello message (inline error, no close).
 *
 * <p>Implements {@link SubProtocolCapable} so the Phase 3.1 direct-WSS browser
 * client gets its offered {@code mateclaw.edge.v1} subprotocol echoed in the
 * 101 response. The browser opens
 * {@code new WebSocket(url, ['mateclaw.edge.v1', 'bearer.<pat>'])}; Spring's
 * {@code AbstractHandshakeHandler#determineSelectedProtocol} walks the offered
 * list in order and returns the first one the server supports — i.e.
 * {@code mateclaw.edge.v1}, NEVER the {@code bearer.*} token. If the browser
 * doesn't get that header back it closes the socket immediately. The
 * Native-Messaging bridge offers no subprotocol, so {@code determineSelectedProtocol}
 * returns null and no header is added — its handshake is unchanged.
 */
@Slf4j
@Component
public class EdgeWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    private static final int SUPPORTED_PROTOCOL_VERSION = 1;
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    /** The single subprotocol the edge endpoint advertises (Phase 3.1). */
    public static final String EDGE_SUBPROTOCOL = "mateclaw.edge.v1";

    private final BrowserSessionRegistry registry;
    private final ObjectMapper mapper;
    private final ActionExecutionService actionExecutionService;
    private final SnapshotEdgeClient snapshotEdgeClient;
    private final ScreenshotEdgeClient screenshotEdgeClient;
    private final PageSnapshotService pageSnapshotService;
    private final String serverVersion;
    private final ConcurrentHashMap<String, String> sessionIdByWsId = new ConcurrentHashMap<>();

    public EdgeWebSocketHandler(BrowserSessionRegistry registry,
                                ObjectMapper mapper,
                                ActionExecutionService actionExecutionService,
                                SnapshotEdgeClient snapshotEdgeClient,
                                ScreenshotEdgeClient screenshotEdgeClient,
                                PageSnapshotService pageSnapshotService,
                                @Value("${revision:dev}") String serverVersion) {
        this.registry = registry;
        this.mapper = mapper;
        this.actionExecutionService = actionExecutionService;
        this.snapshotEdgeClient = snapshotEdgeClient;
        this.screenshotEdgeClient = screenshotEdgeClient;
        this.pageSnapshotService = pageSnapshotService;
        this.serverVersion = serverVersion;
    }

    /**
     * Advertise the {@code mateclaw.edge.v1} subprotocol so the handshake handler
     * echoes it back to a browser client that offered it. The {@code bearer.*}
     * auth entry the browser also offers is intentionally NOT listed here, so it
     * can never be selected/echoed. Returning a non-empty list does not force a
     * subprotocol on clients that offer none (the NH bridge) — Spring only echoes
     * a protocol when the client requests one the server supports.
     */
    @Override
    public List<String> getSubProtocols() {
        return List.of(EDGE_SUBPROTOCOL);
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage payload) throws Exception {
        EdgeMessage msg;
        try {
            msg = mapper.readValue(payload.getPayload(), EdgeMessage.class);
        } catch (Exception e) {
            log.warn("[edge] unparseable frame from ws={}: {}", ws.getId(), e.getMessage());
            ws.close(new CloseStatus(4400, "bad-envelope"));
            return;
        }

        if (msg.getV() != SUPPORTED_PROTOCOL_VERSION) {
            log.warn("[edge] unknown protocol v={} from ws={}", msg.getV(), ws.getId());
            ws.close(new CloseStatus(4400, "unknown-protocol-version"));
            return;
        }

        switch (msg.getKind()) {
            case HELLO -> onHello(ws, msg);
            case HEARTBEAT -> onHeartbeat(ws, msg);
            case PING -> onPing(ws, msg);
            case ACTION_RESULT -> onActionResult(ws, msg);
            case INDICATOR_STOP_CLICKED -> onIndicatorStopClicked(ws, msg);
            case A11Y_SNAPSHOT_RESPONSE -> onA11ySnapshotResponse(ws, msg);
            case SCREENSHOT_CAPTURE_RESPONSE -> onScreenshotCaptureResponse(ws, msg);
            case EVENT_PAGE_NAVIGATED -> onPageNavigated(ws, msg);
            case EVENT_TAB_CLOSED -> onTabClosed(ws, msg);
            case UNKNOWN -> log.warn("[edge] dropping unknown kind from ws={}", ws.getId());
            default -> log.warn("[edge] kind {} not handled in phase 1", msg.getKind());
        }
    }

    private void onHello(WebSocketSession ws, EdgeMessage hello) throws Exception {
        EdgePrincipal principal = (EdgePrincipal) ws.getAttributes().get(EdgeAuthInterceptor.ATTR_PRINCIPAL);
        if (principal == null) {
            ws.close(new CloseStatus(4401, "no-principal"));
            return;
        }
        String agentVersion = (String) hello.getPayload().getOrDefault("agent_version", "unknown");
        // 契约4: register the session under the subject AND every resolved alias
        // (PAT userId ⇄ username) so web and desktop-PAT routes hit one browser.
        BrowserSession session =
                registry.register(principal.subject(), principal.allKeys(), ws, agentVersion);
        sessionIdByWsId.put(ws.getId(), session.getId());

        EdgeMessage ack = reply(hello, EdgeMessageKind.HELLO_ACK, Map.of(
                "session_id", session.getId(),
                "server_version", serverVersion,
                "heartbeat_interval_ms", (int) HEARTBEAT_INTERVAL_MS
        ));
        // Send via the session's concurrency-safe decorated ws (same instance
        // every other component sends through), not the raw handshake socket.
        send(session.getWs(), ack);
    }

    private void onHeartbeat(WebSocketSession ws, EdgeMessage hb) throws Exception {
        if (!validSession(ws, hb)) return;
        // 常驻 bridge 在每次 heartbeat 上报 loopback 上是否真挂着扩展(extension_attached)。后端据此让
        // UI 显示真实连接状态 —— bridge 的 WSS 活着 ≠ 扩展在场(删/禁用扩展后 bridge 仍持 session)。
        // 非 bridge 会话(直连 / Claude Code)不带该字段 → null → 保持默认 true,行为不变。
        Object ea = hb.getPayload() == null ? null : hb.getPayload().get("extension_attached");
        Boolean extensionAttached = (ea instanceof Boolean b) ? b : null;
        Object ev = hb.getPayload() == null ? null : hb.getPayload().get("extension_version");
        String extensionVersion = (ev instanceof String s && !s.isBlank()) ? s : null;
        registry.heartbeat(hb.getSessionId(), extensionAttached, extensionVersion);
        send(sessionWs(hb.getSessionId(), ws), reply(hb, EdgeMessageKind.HEARTBEAT_ACK, Map.of()));
    }

    private void onPing(WebSocketSession ws, EdgeMessage ping) throws Exception {
        if (!validSession(ws, ping)) return;
        Object echo = ping.getPayload().getOrDefault("echo", "");
        send(sessionWs(ping.getSessionId(), ws), reply(ping, EdgeMessageKind.PONG, Map.of(
                "echo", echo,
                "server_ts", Instant.now().toEpochMilli()
        )));
    }

    /** The session's concurrency-safe (decorated) ws, falling back to the raw
     *  socket when no session is registered (pre-hello / error paths). */
    private WebSocketSession sessionWs(String sessionId, WebSocketSession fallback) {
        return registry.find(sessionId).map(BrowserSession::getWs).orElse(fallback);
    }

    private void onActionResult(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        logExtractRegionWirePayload(msg);
        ActionResult result;
        try {
            result = mapper.convertValue(msg.getPayload(), ActionResult.class);
        } catch (Exception e) {
            // A malformed action.result must NOT close the WebSocket — letting the
            // exception propagate makes Spring close the socket (1011), which
            // detaches the whole session and turns one bad result into a cascade
            // of SESSION_DETACHED failures. Deliver a typed failure for this one
            // action instead and keep the connection alive.
            log.warn("[edge] unparseable action.result in_reply_to={}: {}",
                    msg.getInReplyTo(), e.getMessage());
            result = new ActionResult.Failure(
                    "RESULT_PARSE_ERROR",
                    "server could not parse action.result payload: " + e.getMessage(),
                    false);
        }
        actionExecutionService.deliverResult(msg.getInReplyTo(), result);

        // On a successful, DOM-affecting action, age the snapshot cache for the
        // tab that action actually touched so the next observe/grounding refetches
        // (or honours the one-retry SUSPECT budget) instead of replaying a stale
        // tree. See PageSnapshotService#onActionSuccess for the per-kind lifecycle.
        if (result instanceof ActionResult.Success) {
            invalidateSnapshotOnSuccess(msg);
        }
    }

    /**
     * Bridge a successful {@code action.result} into the snapshot freshness
     * machine. The extension stamps two fields we need on the result:
     * <ul>
     *   <li>a top-level {@code resolvedTabId} — the absolute Chrome tab id the
     *       action actually ran against (the SW resolved {@code main|active|<int>}
     *       into it). The snapshot cache is keyed on this integer, so without it
     *       we cannot target the right cache entry.</li>
     *   <li>the success {@code payload.kind} discriminator (e.g. {@code click},
     *       {@code navigate}) — which drives the SUSPECT/STALE transition.</li>
     * </ul>
     * If either is missing (e.g. an older extension that has not yet adopted the
     * {@code resolvedTabId} contract) we log at DEBUG and skip — there is no
     * server-side record of the resolved tab id to fall back to, and invalidating
     * the wrong tab is worse than letting the 30 s TTL age the entry out.
     */
    private void invalidateSnapshotOnSuccess(EdgeMessage msg) {
        Map<String, Object> payload = msg.getPayload();
        if (payload == null) {
            return;
        }
        Long resolvedTabId = readResolvedTabId(payload.get("resolvedTabId"));
        if (resolvedTabId == null) {
            log.debug("[edge] action.result success without numeric resolvedTabId "
                    + "in_reply_to={}; skipping snapshot invalidation", msg.getInReplyTo());
            return;
        }
        ActionKind kind = readSuccessKind(payload.get("payload"));
        if (kind == null) {
            log.debug("[edge] action.result success without recognisable payload.kind "
                    + "in_reply_to={}; skipping snapshot invalidation", msg.getInReplyTo());
            return;
        }
        pageSnapshotService.onActionSuccess(msg.getSessionId(), resolvedTabId, kind);
    }

    private Long readResolvedTabId(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private ActionKind readSuccessKind(Object payloadObj) {
        if (!(payloadObj instanceof Map<?, ?> payload)) {
            return null;
        }
        Object kindObj = payload.get("kind");
        if (!(kindObj instanceof String wire)) {
            return null;
        }
        try {
            return ActionKind.fromWire(wire);
        } catch (IllegalArgumentException ignored) {
            // Forward-compat: an unknown success kind must not crash the socket.
            return null;
        }
    }

    private void logExtractRegionWirePayload(EdgeMessage msg) {
        Object payloadObj = msg.getPayload().get("payload");
        if (!(payloadObj instanceof Map<?, ?> payload)) {
            return;
        }
        if (!"extract_region".equals(String.valueOf(payload.get("kind")))) {
            return;
        }
        Object itemsObj = payload.get("items");
        int items = itemsObj instanceof List<?> list ? list.size() : -1;
        Object diagnostics = payload.get("diagnostics");
        log.info("[edge.extract_region.raw] in_reply_to={} payloadKeys={} items={} diagnosticsPresent={} diagnostics={}",
                msg.getInReplyTo(),
                payload.keySet(),
                items,
                diagnostics != null,
                diagnostics == null ? "{}" : compactJson(diagnostics));
    }

    private String compactJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private void onIndicatorStopClicked(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        registry.find(msg.getSessionId()).ifPresent(actionExecutionService::handleStopClicked);
    }

    private void onA11ySnapshotResponse(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        snapshotEdgeClient.deliverSnapshot(msg.getInReplyTo(), msg.getPayload());
    }

    private void onScreenshotCaptureResponse(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        screenshotEdgeClient.deliverScreenshot(msg.getInReplyTo(), msg.getPayload());
    }

    private void onPageNavigated(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        Long tabId = readTabRef(msg);
        if (tabId == null) {
            log.warn("[edge] dropping event.page.navigated without numeric tab_ref sessionId={}",
                    msg.getSessionId());
            return;
        }
        pageSnapshotService.onPageEvent(msg.getSessionId(), tabId, PageEvent.NAVIGATED);
    }

    private void onTabClosed(WebSocketSession ws, EdgeMessage msg) throws Exception {
        if (!validSession(ws, msg)) return;
        Long tabId = readTabRef(msg);
        if (tabId == null) {
            log.warn("[edge] dropping event.tab.closed without numeric tab_ref sessionId={}",
                    msg.getSessionId());
            return;
        }
        pageSnapshotService.onPageEvent(msg.getSessionId(), tabId, PageEvent.TAB_CLOSED);
    }

    private Long readTabRef(EdgeMessage msg) {
        Object raw = msg.getPayload() == null ? null : msg.getPayload().get("tab_ref");
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Three-way binding check (Codex P1-4 fix):
     *   (a) session_id exists in registry;
     *   (b) the session's underlying ws is the *current* socket;
     *   (c) the session's subject matches the authenticated principal.
     * Failure of (a) returns app.invalid_session; (b) or (c) returns
     * app.session_binding_mismatch and is logged at WARN for abuse audit.
     */
    private boolean validSession(WebSocketSession ws, EdgeMessage msg) throws Exception {
        BrowserSession session = registry.find(msg.getSessionId()).orElse(null);
        if (session == null) {
            send(ws, reply(msg, EdgeMessageKind.ERROR, Map.of(
                    "code", "app.invalid_session",
                    "message", "session_id not known to server",
                    "retryable", false
            )));
            return false;
        }
        EdgePrincipal principal = (EdgePrincipal) ws.getAttributes()
                .get(EdgeAuthInterceptor.ATTR_PRINCIPAL);
        boolean wsBindingOk = session.getWs().getId().equals(ws.getId());
        boolean principalOk = principal != null
                && session.getSubject().equals(principal.subject());
        if (!wsBindingOk || !principalOk) {
            log.warn("[edge] session binding mismatch: sessionId={} ws-ok={} principal-ok={}",
                    msg.getSessionId(), wsBindingOk, principalOk);
            send(ws, reply(msg, EdgeMessageKind.ERROR, Map.of(
                    "code", "app.session_binding_mismatch",
                    "message", "session_id is not owned by this connection",
                    "retryable", false
            )));
            return false;
        }
        return true;
    }

    private EdgeMessage reply(EdgeMessage from, EdgeMessageKind kind, Map<String, Object> payload) {
        return EdgeMessage.builder()
                .v(1)
                .msgId(UUID.randomUUID().toString())
                .kind(kind)
                .ts(Instant.now().toEpochMilli())
                .traceId(from.getTraceId())
                .sessionId(from.getSessionId())
                .inReplyTo(from.getMsgId())
                .payload(payload)
                .build();
    }

    private void send(WebSocketSession ws, EdgeMessage msg) throws Exception {
        ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        String sessionId = sessionIdByWsId.remove(ws.getId());
        if (sessionId != null) {
            actionExecutionService.sessionClosed(sessionId);
            snapshotEdgeClient.sessionClosed(sessionId);
            screenshotEdgeClient.sessionClosed(sessionId);
        }
        registry.removeByWs(ws.getId());
        log.info("[edge] ws {} closed: {}", ws.getId(), status);
    }
}
