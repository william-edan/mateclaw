package vip.mate.browser.orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.protocol.EdgeMessage;
import vip.mate.browser.edge.protocol.EdgeMessageKind;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.edge.session.BrowserSessionRegistry;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class ActionExecutionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper WIRE_MAPPER = new ObjectMapper();

    /**
     * Grace window after a deadline-driven action.cancel during which we hold
     * the per-session single-flight lock, waiting for the extension to ack the
     * cancel (by sending the in-flight action's late action.result) before
     * letting a new action start on the same tab. Without this, the new and the
     * timed-out-but-still-running old action could interleave on one tab.
     */
    private static final long DEADLINE_CANCEL_ACK_GRACE_MS = 1_500L;

    private final BrowserSessionRegistry registry;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ScheduledExecutorService deadlines;

    /** msgId -> state slot for an in-flight request. */
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();
    /** sessionId -> msgId for the current in-flight request on that session. */
    private final ConcurrentHashMap<String, String> bySession = new ConcurrentHashMap<>();
    /**
     * msgId -> session-lock release pending after a cancel. While an entry is
     * present the bySession single-flight lock is still held for that session;
     * the late action.result (the extension's cancel ack) or a grace-timeout
     * releases it. Also lets {@link #deliverResult} recognise a late result for
     * an already-cancelled action and log it rather than silently drop it.
     */
    private final ConcurrentHashMap<String, DeferredRelease> awaitingCancelAck = new ConcurrentHashMap<>();

    @Autowired
    public ActionExecutionService(BrowserSessionRegistry registry, ObjectMapper mapper, Clock clock) {
        this(registry, mapper, clock, Executors.newSingleThreadScheduledExecutor(daemonFactory()));
    }

    ActionExecutionService(BrowserSessionRegistry registry,
                           ObjectMapper mapper,
                           Clock clock,
                           ScheduledExecutorService deadlines) {
        this.registry = registry;
        this.mapper = mapper;
        this.clock = clock;
        this.deadlines = deadlines;
    }

    /**
     * Send action.execute on the session's WebSocket and return a Mono that
     * completes when action.result with matching in_reply_to arrives.
     *
     * <p>Idempotent: same msgId returns the existing Mono and does not resend
     * the envelope. The pending slot is published before the WebSocket send so
     * an immediate action.result cannot be lost.
     */
    public Mono<ActionResult> execute(BrowserSession session, ActionRequest req) {
        PendingRequest existing = pending.get(req.msgId());
        if (existing != null) {
            if (!existing.sessionId().equals(session.getId())) {
                throw new IllegalStateException("action msgId is already in-flight on another session");
            }
            return existing.mono();
        }

        CompletableFuture<ActionResult> future = new CompletableFuture<>();
        PendingRequest created = new PendingRequest(
                new AtomicReference<>(State.INFLIGHT),
                future,
                clock.instant().plusMillis(req.deadlineMs()),
                session.getId(),
                req.msgId(),
                req.tabRef(),
                Mono.fromFuture(future).cache(),
                new AtomicReference<>());

        PendingRequest winner = pending.putIfAbsent(req.msgId(), created);
        if (winner != null) {
            if (!winner.sessionId().equals(session.getId())) {
                throw new IllegalStateException("action msgId is already in-flight on another session");
            }
            return winner.mono();
        }
        if (!reserveSession(session, req, created)) {
            return pending.get(req.msgId()).mono();
        }

        PendingRequest registered = created;
        ScheduledFuture<?> deadlineTask = deadlines.schedule(
                () -> completeOnDeadline(session, registered, req.deadlineMs()),
                req.deadlineMs(),
                TimeUnit.MILLISECONDS);
        registered.deadlineTask().set(deadlineTask);
        if (registered.state().get() == State.DONE) {
            deadlineTask.cancel(false);
        }

        try {
            sendActionExecute(session, req);
        } catch (Exception e) {
            completeWithFailure(registered, "SEND_FAILED",
                    "failed to send action.execute: " + e.getMessage(), true);
        }

        return registered.mono();
    }

    /**
     * Atomically transition the in-flight request to CANCELLING.
     */
    public Mono<Void> cancel(BrowserSession session, String msgId, String reason) {
        PendingRequest slot = pending.get(msgId);
        if (slot == null || !slot.sessionId().equals(session.getId())) {
            return Mono.empty();
        }
        if (!slot.state().compareAndSet(State.INFLIGHT, State.CANCELLING)) {
            return Mono.empty();
        }

        sendActionCancel(session, slot, reason);
        completeCancelling(slot, reason);
        return Mono.empty();
    }

    /**
     * Called by the WebSocket handler when an action.result envelope arrives.
     */
    public void deliverResult(String msgId, ActionResult result) {
        if (msgId == null) {
            return;
        }
        PendingRequest slot = pending.get(msgId);
        if (slot == null) {
            // A result for a msgId that has no pending slot: either an action we
            // already cancelled (deadline/user-stop) whose extension-side handler
            // finally returned — that late result IS the cancel ack, so release
            // the still-held session lock — or a genuinely unmatched stray.
            DeferredRelease release = awaitingCancelAck.remove(msgId);
            if (release != null) {
                log.info("[browser-action] late action.result for cancelled action msgId={} "
                        + "(treated as cancel ack); releasing session lock", msgId);
                release.fire();
            } else {
                log.debug("[browser-action] dropping unmatched action.result msgId={}", msgId);
            }
            return;
        }
        if (!slot.state().compareAndSet(State.INFLIGHT, State.DONE)) {
            return;
        }
        complete(slot, result);
    }

    /**
     * Called by the WebSocket handler when an indicator.stop_clicked envelope
     * arrives. Cancels the in-flight action and then emits an
     * {@code indicator.hide} envelope so the on-page overlays (cursor / glow
     * / stop button) come down — the matching bookend to the
     * {@code indicator.show} the orchestrator sent at plan start.
     *
     * <p>This is the Codex P1-4 closure invariant; the E1 integration test
     * (stopButton_cancelsInflightAndFiresIndicatorHide) asserts that
     * indicator.hide is the last outbound envelope on the wire.
     *
     * <p>If there is nothing in flight on this session, both operations
     * are no-ops (no cancel to perform, and no overlays to hide because
     * none were shown).
     */
    public void handleStopClicked(BrowserSession session) {
        String msgId = bySession.get(session.getId());
        if (msgId == null) {
            return;
        }

        // Capture tab_ref BEFORE the cancel completes — cancel() drops the
        // pending slot synchronously, after which slot.tabRef() is gone.
        PendingRequest slot = pending.get(msgId);
        TabRef tabRef = slot != null ? slot.tabRef() : null;

        cancel(session, msgId, "user_stop").subscribe();

        if (tabRef != null) {
            sendIndicatorHide(session, tabRef);
        }
    }

    /**
     * Fire-and-forget {@code indicator.hide} on the current session's WS.
     * Emitted as the P1-4 bookend to an earlier {@code indicator.show};
     * also emitted after a successful plan completes (Phase 3 work — for
     * now only the cancel path emits it).
     */
    private void sendIndicatorHide(BrowserSession session, TabRef tabRef) {
        try {
            EdgeMessage message = EdgeMessage.builder()
                    .v(1)
                    .msgId(UUID.randomUUID().toString())
                    .kind(EdgeMessageKind.INDICATOR_HIDE)
                    .ts(clock.instant().toEpochMilli())
                    .traceId(UUID.randomUUID().toString())
                    .sessionId(session.getId())
                    .payload(Map.of(
                            "tab_ref", mapper.convertValue(tabRef, Object.class)))
                    .build();
            session.getWs().sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.debug("[browser-action] failed to send indicator.hide: {}", e.getMessage());
        }
    }

    /**
     * Called by the WebSocket handler when the underlying socket is closed.
     */
    public void sessionClosed(String sessionId) {
        String msgId = bySession.get(sessionId);
        if (msgId == null) {
            return;
        }
        PendingRequest slot = pending.get(msgId);
        if (slot == null) {
            bySession.remove(sessionId, msgId);
            return;
        }
        completeWithFailure(slot, "SESSION_DETACHED", "browser session detached", false);
    }

    private boolean reserveSession(BrowserSession session, ActionRequest req, PendingRequest created) {
        String liveMsgId = bySession.putIfAbsent(session.getId(), req.msgId());
        if (liveMsgId == null || liveMsgId.equals(req.msgId())) {
            return true;
        }

        pending.remove(req.msgId(), created);
        PendingRequest live = pending.get(liveMsgId);
        if (live != null) {
            throw new IllegalStateException(
                    "an action is already in-flight on session " + session.getId());
        }
        bySession.remove(session.getId(), liveMsgId);
        return reserveSession(session, req, created);
    }

    private void sendActionExecute(BrowserSession session, ActionRequest req) throws Exception {
        EdgeMessage message = EdgeMessage.builder()
                .v(1)
                .msgId(req.msgId())
                .kind(EdgeMessageKind.ACTION_EXECUTE)
                .ts(clock.instant().toEpochMilli())
                .traceId(UUID.randomUUID().toString())
                .sessionId(session.getId())
                .payload(Map.of(
                        // msg_id MUST be inside the payload, not just on the envelope:
                        // the extension's parseActionRequest reads msg.payload.msg_id and
                        // rejects the whole request (HANDLER_ERROR "malformed payload") when
                        // it's absent. Envelope msgId alone is not enough.
                        "msg_id", req.msgId(),
                        "tab_ref", WIRE_MAPPER.valueToTree(req.tabRef()),
                        "kind", req.kind().wire(),
                        // Browser action protocol fields such as duration_ms and tab ids
                        // must remain JSON numbers. The app-wide mapper stringifies Long
                        // values to protect Snowflake IDs in API responses, so use a local
                        // wire mapper for extension-bound protocol payloads.
                        "params", WIRE_MAPPER.valueToTree(req.params()),
                        // int, not long: the global ObjectMapper stringifies Long/long
                        // (Snowflake-ID guard in JacksonConfig), but the extension's
                        // parseActionRequest requires deadline_ms to be a JSON *number*.
                        // Deadlines are seconds-to-minutes, far within int range.
                        "deadline_ms", Math.toIntExact(req.deadlineMs())))
                .build();
        session.getWs().sendMessage(new TextMessage(mapper.writeValueAsString(message)));
    }

    private void sendActionCancel(BrowserSession session, String msgId, String reason) {
        PendingRequest slot = pending.get(msgId);
        if (slot == null) {
            return;
        }
        sendActionCancel(session, slot, reason);
    }

    private void sendActionCancel(BrowserSession session, PendingRequest slot, String reason) {
        try {
            EdgeMessage message = EdgeMessage.builder()
                    .v(1)
                    .msgId(UUID.randomUUID().toString())
                    .kind(EdgeMessageKind.ACTION_CANCEL)
                    .ts(clock.instant().toEpochMilli())
                    .traceId(UUID.randomUUID().toString())
                    .sessionId(session.getId())
                    .inReplyTo(slot.msgId())
                    .payload(Map.of(
                            // Double-write the target msgId: the contract puts it on the
                            // envelope's in_reply_to (above) AND inside the payload. The
                            // extension's handleCancel prefers msg.in_reply_to and falls
                            // back to payload.in_reply_to — carry both for resilience.
                            "in_reply_to", slot.msgId(),
                            "tab_ref", mapper.convertValue(slot.tabRef(), Object.class),
                            "reason", reason))
                    .build();
            session.getWs().sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.debug("[browser-action] failed to send action.cancel msgId={}: {}",
                    slot.msgId(), e.getMessage());
        }
    }

    private void completeCancelling(PendingRequest slot, String reason) {
        if (!slot.state().compareAndSet(State.CANCELLING, State.DONE)) {
            return;
        }
        complete(slot, new ActionResult.Failure(
                "CANCELLED",
                "action cancelled: " + reason,
                false));
        // The session lock is already released by complete() above (a user_stop
        // cancel does not need the deadline path's hold-until-ack), but record
        // the cancelled msgId so its late, now-superfluous action.result is
        // logged as a cancel ack rather than silently dropped. The marker
        // self-expires via the grace timeout (an idempotent no-op release).
        deferSessionLockRelease(slot);
    }

    private void completeWithFailure(PendingRequest slot, String code, String message, boolean retryable) {
        if (!slot.state().compareAndSet(State.INFLIGHT, State.DONE)) {
            return;
        }
        complete(slot, new ActionResult.Failure(code, message, retryable));
    }

    /**
     * Deadline-expiry completion that does NOT break per-tab serialisation.
     *
     * <p>Releasing the {@code bySession} single-flight lock the instant the
     * deadline fires would let the next action start on the same tab while the
     * timed-out action is still mid-flight in the extension — the two would
     * interleave on one tab. So we: (1) complete the caller's future with
     * {@code DEADLINE_EXCEEDED} immediately; (2) send {@code action.cancel}
     * (reason {@code deadline_exceeded}); (3) keep holding the session lock and
     * release it only once the extension acks — i.e. the in-flight action's late
     * {@code action.result} arrives — or a short grace timeout elapses.
     */
    private void completeOnDeadline(BrowserSession session, PendingRequest slot, long deadlineMs) {
        if (!slot.state().compareAndSet(State.INFLIGHT, State.DONE)) {
            return;
        }
        ScheduledFuture<?> task = slot.deadlineTask().get();
        if (task != null) {
            task.cancel(false);
        }
        // Drop the pending slot now (the future is terminal) but DEFER the
        // session-lock release until the extension acks the cancel.
        pending.remove(slot.msgId(), slot);
        slot.future().complete(new ActionResult.Failure(
                "DEADLINE_EXCEEDED",
                "action deadline exceeded after " + deadlineMs + "ms",
                true));

        // Register the deferred release BEFORE sending the cancel: the extension
        // cannot emit the in-flight action's late action.result (the ack) until it
        // has received the cancel, so the recognition marker is always in place
        // before that ack can arrive.
        deferSessionLockRelease(slot);
        sendActionCancel(session, slot, "deadline_exceeded");
    }

    /**
     * Register a deferred release of the session lock held by {@code slot}.
     * Fired by the late {@code action.result} (cancel ack) in
     * {@link #deliverResult}, or by a grace-timeout fallback so a dropped/lost
     * ack never wedges the session permanently.
     */
    private void deferSessionLockRelease(PendingRequest slot) {
        DeferredRelease release = new DeferredRelease(slot.sessionId(), slot.msgId());
        DeferredRelease previous = awaitingCancelAck.put(slot.msgId(), release);
        if (previous != null) {
            previous.cancelTimeout();
        }
        ScheduledFuture<?> timeout = deadlines.schedule(() -> {
            if (awaitingCancelAck.remove(slot.msgId(), release)) {
                log.info("[browser-action] no cancel ack within {}ms for msgId={}; "
                        + "releasing session lock anyway", DEADLINE_CANCEL_ACK_GRACE_MS, slot.msgId());
                releaseSessionLock(slot.sessionId(), slot.msgId());
            }
        }, DEADLINE_CANCEL_ACK_GRACE_MS, TimeUnit.MILLISECONDS);
        release.timeout().set(timeout);
    }

    private void complete(PendingRequest slot, ActionResult result) {
        ScheduledFuture<?> task = slot.deadlineTask().get();
        if (task != null) {
            task.cancel(false);
        }
        pending.remove(slot.msgId(), slot);
        releaseSessionLock(slot.sessionId(), slot.msgId());
        slot.future().complete(result);
    }

    /** Release the per-session single-flight lock iff still held by {@code msgId}. */
    private void releaseSessionLock(String sessionId, String msgId) {
        bySession.remove(sessionId, msgId);
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "browser-action-deadline");
            thread.setDaemon(true);
            return thread;
        };
    }

    private enum State {
        INFLIGHT,
        CANCELLING,
        DONE
    }

    private record PendingRequest(
            AtomicReference<State> state,
            CompletableFuture<ActionResult> future,
            Instant deadlineAt,
            String sessionId,
            String msgId,
            TabRef tabRef,
            Mono<ActionResult> mono,
            AtomicReference<ScheduledFuture<?>> deadlineTask
    ) {
    }

    /**
     * A session-lock release pending the extension's cancel ack. Holds the
     * (sessionId, msgId) whose {@code bySession} lock is still reserved, plus
     * the grace-timeout fallback so the ack arriving (or not) both converge on
     * exactly one release.
     */
    private final class DeferredRelease {
        private final String sessionId;
        private final String msgId;
        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();

        private DeferredRelease(String sessionId, String msgId) {
            this.sessionId = sessionId;
            this.msgId = msgId;
        }

        AtomicReference<ScheduledFuture<?>> timeout() {
            return timeout;
        }

        void cancelTimeout() {
            ScheduledFuture<?> t = timeout.get();
            if (t != null) {
                t.cancel(false);
            }
        }

        /** Release the session lock now (ack arrived) and stop the fallback timeout. */
        void fire() {
            cancelTimeout();
            releaseSessionLock(sessionId, msgId);
        }
    }
}
