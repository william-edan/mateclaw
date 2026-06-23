package vip.mate.browser.edge.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of live Browser Agent edge sessions.
 *
 * <p>Phase 1 is in-memory only — sessions die with the JVM. Phase 4 introduces
 * Postgres-backed session checkpointing and resumption.
 *
 * <p>Single-active-session-per-user policy: re-registering for the same user
 * replaces the previous session and closes the old WebSocket with code 4409.
 * This is intentional — a Native Host crash + reconnect must not leave a stale
 * handler holding the registry slot.
 */
@Slf4j
@Component
public class BrowserSessionRegistry {

    /** Heartbeat grace window: if no message seen for this long, session is stale. */
    public static final Duration STALE_GRACE = Duration.ofSeconds(30);

    /**
     * Liveness window used by {@link #findLiveBySubject(String)}: a session whose
     * lastHeartbeatAt is older than this is treated as dead and evicted on the
     * spot, rather than handing the caller a corpse that the ~40 s reaper has not
     * yet collected. Kept a touch under {@link #STALE_GRACE} so the dispatch path
     * fails fast before the reaper window closes, but still tolerant of a missed
     * heartbeat on a slow link.
     */
    public static final Duration LIVE_HEARTBEAT_WINDOW = Duration.ofSeconds(25);

    /**
     * Tomcat/JSR-356 WebSocket sessions forbid concurrent sends — two threads
     * calling sendMessage at once throws "TEXT_PARTIAL_WRITING" / corrupts the
     * stream. The edge protocol sends from several threads (heartbeat-ack,
     * action.execute, a11y.snapshot.request, …), so every session is wrapped in
     * a {@link ConcurrentWebSocketSessionDecorator} that serialises sends behind
     * a per-session lock + bounded buffer.
     */
    private static final int SEND_TIME_LIMIT_MS = 15_000;
    private static final int SEND_BUFFER_BYTES = 1024 * 1024;

    private final ConcurrentHashMap<String, BrowserSession> byId = new ConcurrentHashMap<>();
    /** subject -> live sessionId. compute() on this map is the per-subject serialisation lock. */
    private final ConcurrentHashMap<String, String> subjectToSession = new ConcurrentHashMap<>();

    private volatile Clock clock;

    public BrowserSessionRegistry() { this(Clock.systemUTC()); }

    public BrowserSessionRegistry(Clock clock) { this.clock = clock; }

    /** Test seam — do not call from production code. */
    void setClockForTest(Clock clock) { this.clock = clock; }

    /**
     * Register a new session for {@code subject}. Atomically replaces any
     * existing session for the same subject; the old socket is closed with
     * 4409 inside the per-subject compute() lock so two concurrent
     * registrations cannot leave two live entries in {@link #byId}.
     *
     * <p>Backward-compatible 3-arg form: the session is reachable only under
     * {@code subject}. New callers that can resolve the user's other identity
     * (PAT userId ⇄ username) should use {@link #register(String, java.util.Collection,
     * WebSocketSession, String)} so web (username) and desktop PAT (userId)
     * lookups cross-hit.
     */
    public BrowserSession register(String subject, WebSocketSession ws, String agentVersion) {
        return register(subject, List.of(subject), ws, agentVersion);
    }

    /**
     * Register a new session reachable under every key in {@code aliasKeys}
     * (契约4). {@code subject} is the primary key (what
     * {@link BrowserSessionView#subject()} reports); the alias keys typically add
     * the PAT {@code userId} string and the owning username so the desktop PAT
     * path and the web username path resolve the SAME browser. Blank / null
     * aliases are ignored; {@code subject} is always included.
     *
     * <p>Conflict semantics match the single-key form: any existing session
     * already reachable under {@code subject} OR any of the alias keys is dropped
     * (its socket closed 4409) so a reconnect never leaves two live entries —
     * and never leaves a stale alias pointing at a corpse.
     */
    public BrowserSession register(String subject,
                                   java.util.Collection<String> aliasKeys,
                                   WebSocketSession ws,
                                   String agentVersion) {
        String newId = "sess-" + UUID.randomUUID();
        // Normalise the key set: subject first, then de-duped non-blank aliases.
        Set<String> keys = new LinkedHashSet<>();
        keys.add(subject);
        if (aliasKeys != null) {
            for (String k : aliasKeys) {
                if (k != null && !k.isBlank()) {
                    keys.add(k);
                }
            }
        }
        // Wrap so ALL sends through session.getWs() are serialised — every
        // component (handler, ActionExecutionService, snapshot/screenshot
        // clients) sends through this one decorated instance.
        WebSocketSession concurrentWs =
                new ConcurrentWebSocketSessionDecorator(ws, SEND_TIME_LIMIT_MS, SEND_BUFFER_BYTES);
        BrowserSession session = BrowserSession.builder()
                .id(newId)
                .subject(subject)
                .agentVersion(agentVersion)
                .ws(concurrentWs)
                .lastHeartbeatAt(clock.instant())
                .aliasKeys(Set.copyOf(keys))
                .build();

        // (1) Publish in byId first so concurrent readers see a coherent state.
        byId.put(newId, session);

        // (2) For every key, atomically remap key -> newId. Each compute() is the
        //     per-key serialisation lock; inside it we only touch byId + close the
        //     displaced socket. We must NOT mutate subjectToSession for any key
        //     from inside a compute() on this same map (re-entrant modification of
        //     a ConcurrentHashMap inside compute() is forbidden) — so we collect
        //     displaced sessions and purge their leftover alias keys AFTER the loop.
        Set<BrowserSession> displaced = new LinkedHashSet<>();
        for (String key : keys) {
            subjectToSession.compute(key, (k, existingId) -> {
                if (existingId != null && !existingId.equals(newId)) {
                    BrowserSession prev = byId.remove(existingId);
                    if (prev != null) {
                        displaced.add(prev);
                        closeQuietly(prev.getWs(), new CloseStatus(4409, "session-conflict"));
                        log.info("[edge] key {} reconnected; dropped previous session {} (subject={})",
                                key, existingId, prev.getSubject());
                    }
                }
                return newId;
            });
        }
        // (3) Purge any alias keys the displaced sessions still own that we did NOT
        //     just overwrite — conditional remove so we never clobber a key the new
        //     session (or a racing registration) now holds.
        for (BrowserSession prev : displaced) {
            for (String key : prev.getAliasKeys()) {
                if (!keys.contains(key)) {
                    subjectToSession.remove(key, prev.getId());
                }
            }
        }
        return session;
    }

    public Optional<BrowserSession> find(String sessionId) {
        return Optional.ofNullable(byId.get(sessionId));
    }

    public Optional<BrowserSession> findBySubject(String subject) {
        if (subject == null) {
            return Optional.empty();
        }
        String id = subjectToSession.get(subject);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /**
     * Like {@link #findBySubject(String)} but returns the session ONLY when it is
     * actually usable for dispatch right now: the underlying socket is open AND
     * its lastHeartbeatAt is within {@link #LIVE_HEARTBEAT_WINDOW}. A match that
     * fails either check is evicted immediately (socket closed 4408, all alias
     * keys purged) and {@link Optional#empty()} is returned — so the caller never
     * fires an instruction into a dead session sitting inside the reaper window.
     *
     * <p>Used by {@code ExtensionBrowserTool.resolveSession} before returning a
     * session, and reusable by group D's continue-run path.
     */
    public Optional<BrowserSession> findLiveBySubject(String subject) {
        Optional<BrowserSession> found = findBySubject(subject);
        if (found.isEmpty()) {
            return found;
        }
        BrowserSession s = found.get();
        if (s.isDisabled()) {
            // 用户已开关式断开:不路由动作,但【不】evict、不关 WS —— 扩展保持连着,"连接"可解禁恢复。
            return Optional.empty();
        }
        if (isLive(s)) {
            return found;
        }
        // Dead but still mapped — evict now rather than handing back a corpse.
        evict(s, "stale-on-resolve");
        return Optional.empty();
    }

    /**
     * @return {@code true} when {@code s}'s socket is open and its last heartbeat
     *         is within {@link #LIVE_HEARTBEAT_WINDOW}.
     */
    public boolean isLive(BrowserSession s) {
        if (s == null) {
            return false;
        }
        if (s.getWs() == null || !s.getWs().isOpen()) {
            return false;
        }
        Instant threshold = clock.instant().minus(LIVE_HEARTBEAT_WINDOW);
        return !s.getLastHeartbeatAt().isBefore(threshold);
    }

    /**
     * Wait up to {@code timeout} for a LIVE session to (re)appear under
     * {@code subject}, polling at {@code pollInterval} (契约4 — 断连宽限). Returns
     * as soon as a live session is present, or {@link Optional#empty()} when the
     * deadline elapses. Intended for the "same subject just dropped and is
     * reconnecting" case: rather than failing a dispatch the instant the socket
     * blips, the caller can grant a short grace window for the registry to
     * re-publish that subject's reconnected session.
     *
     * <p>Reusable by group D's continue-run path. Honours thread interruption
     * (re-sets the interrupt flag and returns empty). Pass a small timeout
     * (5–10 s) — this blocks the calling thread.
     */
    public Optional<BrowserSession> awaitLiveBySubject(String subject,
                                                       Duration timeout,
                                                       Duration pollInterval) {
        if (subject == null) {
            return Optional.empty();
        }
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long sleepMs = Math.max(50L, pollInterval.toMillis());
        while (true) {
            Optional<BrowserSession> live = findLiveBySubject(subject);
            if (live.isPresent()) {
                return live;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return Optional.empty();
            }
            try {
                Thread.sleep(Math.min(sleepMs,
                        Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    public void heartbeat(String sessionId) {
        heartbeat(sessionId, null, null);
    }

    /**
     * Heartbeat 续命,并(可选)更新 bridge 上报的"扩展是否在场 / 扩展版本"。任一参数为 {@code null}
     * 表示本次心跳不带该信息(非 bridge 会话)→ 不动对应字段,仅续 lastHeartbeatAt。
     */
    public void heartbeat(String sessionId, Boolean extensionAttached, String extensionVersion) {
        BrowserSession s = byId.get(sessionId);
        if (s != null) {
            s.setLastHeartbeatAt(clock.instant());
            if (extensionAttached != null) {
                s.setExtensionAttached(extensionAttached);
            }
            if (extensionVersion != null && !extensionVersion.isBlank()) {
                s.setExtensionVersion(extensionVersion);
            }
        }
    }

    /**
     * 开关式"断开/连接":把所有 live session 的 {@link BrowserSession#isDisabled() disabled} 置为
     * {@code disabled}。true=断开(UI 未连接、获客不路由动作,但保持 WS 连着、可逆);false=恢复连接。
     * 返回受影响的会话数。桌面端"断开/连接"经此实现(不依赖在 Electron 内够不到的扩展直发),故可逆不卡死。
     */
    public int setDisabledAllLive(boolean disabled) {
        int n = 0;
        for (BrowserSession s : byId.values()) {
            if (isLive(s)) {
                s.setDisabled(disabled);
                n++;
            }
        }
        return n;
    }

    public int sizeForSubject(String subject) {
        return subjectToSession.containsKey(subject) ? 1 : 0;
    }

    public int size() { return byId.size(); }

    /**
     * Read-only snapshot of live sessions for debugging / observability.
     * Returned views intentionally omit the underlying WebSocket reference
     * (no leaking to controllers).
     */
    public List<BrowserSessionView> snapshot() {
        var out = new ArrayList<BrowserSessionView>(byId.size());
        for (var s : byId.values()) {
            out.add(new BrowserSessionView(
                    s.getId(), s.getSubject(), s.getAgentVersion(), s.getLastHeartbeatAt(),
                    // 开关式断开:disabled=true 时对 UI 报 extensionAttached=false → 显示"未连接"。
                    s.isExtensionAttached() && !s.isDisabled(), s.getExtensionVersion()));
        }
        return out;
    }

    /**
     * Remove sessions whose lastHeartbeatAt is older than STALE_GRACE.
     * Returns the number of sessions reaped.
     */
    public int reapStale() {
        var threshold = clock.instant().minus(STALE_GRACE);
        int reaped = 0;
        for (var entry : byId.entrySet()) {
            BrowserSession s = entry.getValue();
            if (s.getLastHeartbeatAt().isBefore(threshold)) {
                byId.remove(entry.getKey());
                purgeAliasKeys(s, entry.getKey());
                closeQuietly(s.getWs(), new CloseStatus(4408, "heartbeat-timeout"));
                reaped++;
                log.info("[edge] reaped stale session {} (subject={})", s.getId(), s.getSubject());
            }
        }
        return reaped;
    }

    /** Called by the WebSocket handler on close. */
    public void removeByWs(String wsId) {
        for (var entry : byId.entrySet()) {
            if (entry.getValue().getWs().getId().equals(wsId)) {
                byId.remove(entry.getKey());
                purgeAliasKeys(entry.getValue(), entry.getKey());
                return;
            }
        }
    }

    /**
     * Evict {@code s} immediately: drop it from {@link #byId}, purge every alias
     * key, and close its socket. Used by {@link #findLiveBySubject(String)} when
     * a mapped session is found dead before the reaper gets to it.
     */
    private void evict(BrowserSession s, String reason) {
        byId.remove(s.getId(), s);
        purgeAliasKeys(s, s.getId());
        closeQuietly(s.getWs(), new CloseStatus(4408, reason));
        log.info("[edge] evicted dead session {} (subject={}, reason={})",
                s.getId(), s.getSubject(), reason);
    }

    /**
     * Remove every alias entry in {@link #subjectToSession} that still points at
     * {@code sessionId} for the given session's recorded {@code aliasKeys}. Uses
     * the conditional 2-arg {@code remove} so we never clobber a key another
     * session has since claimed (the reconnect race).
     */
    private void purgeAliasKeys(BrowserSession s, String sessionId) {
        Set<String> keys = s.getAliasKeys();
        if (keys == null || keys.isEmpty()) {
            // Legacy fallback: at least drop the primary subject key.
            subjectToSession.remove(s.getSubject(), sessionId);
            return;
        }
        for (String key : keys) {
            subjectToSession.remove(key, sessionId);
        }
    }

    private void closeQuietly(WebSocketSession ws, CloseStatus status) {
        try {
            if (ws.isOpen()) ws.close(status);
        } catch (IOException e) {
            log.debug("[edge] suppressed close error: {}", e.getMessage());
        }
    }
}
