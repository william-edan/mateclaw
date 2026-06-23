package vip.mate.browser.orchestrator.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.Viewport;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Concrete {@link SnapshotEdgeClient} that issues {@code a11y.snapshot.request}
 * envelopes on the session's Edge WebSocket and awaits the matching
 * {@code a11y.snapshot.response} by {@code in_reply_to} correlation.
 *
 * <p>Mirrors {@code ActionExecutionService}'s pending-future pattern (Wave 1
 * P3): publish the slot into {@link #pending} BEFORE sending the envelope so
 * an immediate response cannot be lost; deadline timer cancels the future if
 * no response arrives in {@link #DEFAULT_TIMEOUT}.
 *
 * <p>Registered as a {@code @Service} — the {@link SnapshotEdgeClientFallbackConfig}
 * fallback's {@code @ConditionalOnMissingBean} automatically steps aside.
 */
@Slf4j
@Service
public class DefaultSnapshotEdgeClient implements SnapshotEdgeClient {

    /** Wire-spec timeout — fast enough to surface "extension is wedged" but
     *  generous enough for slow first-page hydration. Tunable later. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_DEPTH = 15;
    public static final int DEFAULT_MAX_CHARS = 200_000;

    private final ObjectMapper mapper;
    private final Clock clock;
    private final ScheduledExecutorService deadlines;

    /** msgId -> pending slot. */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    @Autowired
    public DefaultSnapshotEdgeClient(ObjectMapper mapper, Clock clock) {
        this(mapper, clock, Executors.newSingleThreadScheduledExecutor(daemonFactory()));
    }

    DefaultSnapshotEdgeClient(ObjectMapper mapper,
                              Clock clock,
                              ScheduledExecutorService deadlines) {
        this.mapper = mapper;
        this.clock = clock;
        this.deadlines = deadlines;
    }

    @Override
    public Mono<PageSnapshot> request(BrowserSession session, TabRef tabRef, String filter) {
        String msgId = UUID.randomUUID().toString();
        CompletableFuture<PageSnapshot> future = new CompletableFuture<>();
        Pending slot = new Pending(future, session.getId());

        Pending winner = pending.putIfAbsent(msgId, slot);
        // UUID collision is astronomically rare; if it happens, retry generates a new id.
        if (winner != null) {
            return request(session, tabRef, filter);
        }

        ScheduledFuture<?> timeoutTask = deadlines.schedule(() -> {
            if (pending.remove(msgId, slot)) {
                slot.future.completeExceptionally(new SnapshotTimeoutException(
                        "a11y.snapshot.request timed out after " + DEFAULT_TIMEOUT));
            }
        }, DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        try {
            EdgeMessage envelope = EdgeMessage.builder()
                    .v(1)
                    .msgId(msgId)
                    .kind(EdgeMessageKind.A11Y_SNAPSHOT_REQUEST)
                    .ts(clock.instant().toEpochMilli())
                    .traceId(UUID.randomUUID().toString())
                    .sessionId(session.getId())
                    .payload(Map.of(
                            "tab_ref", mapper.convertValue(tabRef, Object.class),
                            "filter", filter == null ? "interactive" : filter,
                            "depth", DEFAULT_DEPTH,
                            "max_chars", DEFAULT_MAX_CHARS,
                            // 获客流程置位(SnapshotPreferenceContext):让扩展跳过 CDP a11y、走 DOM 走树器,
                            // 不触发"已开始调试此浏览器"横幅(页面不变形)。通用 agent 不置位→false→CDP-first 不变。
                            "prefer_dom", SnapshotPreferenceContext.preferDom()))
                    .build();
            session.getWs().sendMessage(new TextMessage(mapper.writeValueAsString(envelope)));
        } catch (Exception e) {
            timeoutTask.cancel(false);
            if (pending.remove(msgId, slot)) {
                slot.future.completeExceptionally(new IllegalStateException(
                        "failed to send a11y.snapshot.request: " + e.getMessage(), e));
            }
        }

        return Mono.fromFuture(future);
    }

    /**
     * Called by {@code EdgeWebSocketHandler} when an
     * {@code a11y.snapshot.response} envelope arrives. Looks up the pending
     * slot by {@code in_reply_to} and resolves its future with a parsed
     * {@link PageSnapshot}. No-op when the slot is gone (timed out / cancelled).
     */
    public void deliverSnapshot(String inReplyTo, Map<String, Object> payload) {
        if (inReplyTo == null) {
            log.debug("[browser-snapshot] dropping a11y.snapshot.response with no in_reply_to");
            return;
        }
        Pending slot = pending.remove(inReplyTo);
        if (slot == null) {
            log.debug("[browser-snapshot] dropping unmatched a11y.snapshot.response msgId={}",
                    inReplyTo);
            return;
        }
        try {
            slot.future.complete(parseSnapshot(payload));
        } catch (Exception e) {
            slot.future.completeExceptionally(e);
        }
    }

    /**
     * Called by {@code EdgeWebSocketHandler.afterConnectionClosed}. Any
     * in-flight snapshot fetch on the closed session fails with
     * {@code SESSION_DETACHED}.
     */
    public void sessionClosed(String sessionId) {
        pending.entrySet().removeIf(e -> {
            if (!e.getValue().sessionId.equals(sessionId)) return false;
            e.getValue().future.completeExceptionally(new SessionDetachedException(
                    "browser session detached before a11y.snapshot.response arrived"));
            return true;
        });
    }

    private PageSnapshot parseSnapshot(Map<String, Object> payload) {
        throwIfFailure(payload);
        String snapshotId = readString(payload, "snapshot_id", "");
        long capturedAt = readLong(payload, "captured_at_ms", clock.instant().toEpochMilli());
        long tabRef = readLong(payload, "tab_ref", -1L);
        String tree = readString(payload, "tree", "");
        Viewport vp = readViewport(payload);
        // New (post-2026-05-30) extensions emit url + title as dedicated
        // fields next to the tree — the snapshot lifecycle code never reads
        // them, but ExtensionBrowserTool.extension_browser_observe surfaces
        // them in the JSON output so the LLM can detect navigation. Older
        // extensions that don't emit them default to "" (PageSnapshot's
        // canonical ctor normalises nulls).
        String url = readString(payload, "url", "");
        String title = readString(payload, "title", "");
        return new PageSnapshot(
                snapshotId.isBlank() ? UUID.randomUUID().toString() : snapshotId,
                capturedAt,
                tabRef,
                tree,
                vp,
                url,
                title);
    }

    @SuppressWarnings("unchecked")
    private static void throwIfFailure(Map<String, Object> payload) {
        Object raw = payload.get("error");
        if (!(raw instanceof Map<?, ?> m)) {
            return;
        }
        Map<String, Object> error = (Map<String, Object>) m;
        String code = readString(error, "code", "SNAPSHOT_FAILED");
        String message = readString(error, "message", "");
        boolean retryable = readBoolean(error, "retryable", false);
        throw new SnapshotFailureException(code, message, retryable);
    }

    @SuppressWarnings("unchecked")
    private Viewport readViewport(Map<String, Object> payload) {
        Object raw = payload.get("viewport");
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> v = (Map<String, Object>) m;
            int w = (int) readLong(v, "w", 1280);
            int h = (int) readLong(v, "h", 800);
            // A freshly-created tab can momentarily report 0×0 before layout.
            // Clamp to a sane default rather than rejecting an otherwise-valid
            // snapshot (Viewport's ctor requires positive dims; viewport only
            // matters for vision scaling, not for the a11y tree / grounding).
            if (w <= 0) w = 1280;
            if (h <= 0) h = 800;
            return new Viewport(w, h);
        }
        return new Viewport(1280, 800);
    }

    private static String readString(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : v.toString();
    }

    private static long readLong(Map<String, Object> map, String key, long fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignore) { return fallback; }
        }
        return fallback;
    }

    private static boolean readBoolean(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    private static ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r, "browser-snapshot-deadline");
            t.setDaemon(true);
            return t;
        };
    }

    private record Pending(CompletableFuture<PageSnapshot> future, String sessionId) {}

    /** Thrown when the response window expires. Wraps as Mono error. */
    public static class SnapshotTimeoutException extends RuntimeException {
        public SnapshotTimeoutException(String message) { super(message); }
    }

    /** Thrown when the underlying session closes mid-fetch. */
    public static class SessionDetachedException extends RuntimeException {
        public SessionDetachedException(String message) { super(message); }
    }

    /** Thrown when the extension returns an error-shaped a11y snapshot response. */
    public static class SnapshotFailureException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public SnapshotFailureException(String code, String message, boolean retryable) {
            super(code + (message == null || message.isBlank() ? "" : ": " + message));
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
