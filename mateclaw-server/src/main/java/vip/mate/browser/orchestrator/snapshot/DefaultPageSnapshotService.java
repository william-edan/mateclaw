package vip.mate.browser.orchestrator.snapshot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionKind;
import vip.mate.browser.edge.action.TabRef;
import vip.mate.browser.edge.session.BrowserSession;
import vip.mate.browser.orchestrator.domain.PageEvent;
import vip.mate.browser.orchestrator.domain.PageSnapshot;
import vip.mate.browser.orchestrator.domain.SnapshotState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link PageSnapshotService}.
 *
 * <p><strong>Design choice — looking up the resolvedTabId from a TabRef.</strong>
 * The cache <em>must</em> be keyed on the absolute resolved tab id (the integer
 * the SW resolved {@code "main" | "active" | <int>} into) because:
 * <ul>
 *   <li>{@code onActionSuccess(sessionId, resolvedTabId, kind)} and
 *       {@code onPageEvent(sessionId, resolvedTabId, ev)} only have the
 *       integer — they observe wire envelopes that already carry the resolved
 *       id, not the original TabRef the user passed.</li>
 *   <li>Two distinct {@link TabRef.Active} requests, made at different times,
 *       can resolve to different tabs — we don't want them to share a cache
 *       entry.</li>
 * </ul>
 * On the {@link #request} path we don't know the resolved tab id until the
 * Extension's response arrives. To still let a SECOND request for the same
 * {@code TabRef} reuse the cache, we maintain an auxiliary index from
 * {@code (sessionId, TabRef-canonical-form)} to the last resolved tab id we
 * saw for that selector. The first request always goes through
 * {@link SnapshotEdgeClient}; subsequent requests for the same selector
 * short-circuit to the cache if the entry is still FRESH/SUSPECT within
 * {@link #MAX_AGE}.
 *
 * <p>This keeps the freshness state machine keyed on the integer tab id
 * (which is what the spec's lifecycle table is written against) while the
 * caller-facing API stays in terms of {@link TabRef}.
 *
 * <p><strong>Single-flight.</strong> Concurrent {@link #request} calls for
 * the same {@code (sessionId, TabRef)} that miss the cache share one
 * {@link SnapshotEdgeClient#request} subscription via a cached {@link Mono}
 * stored in the in-flight map; once the response lands, both callers see
 * the same {@link PageSnapshot} and the slot is removed.
 */
@Service
public class DefaultPageSnapshotService implements PageSnapshotService {

    /** TTL after which any cached snapshot is treated as STALE regardless of state. */
    public static final Duration MAX_AGE = Duration.ofSeconds(30);

    private final SnapshotEdgeClient client;
    private final Clock clock;

    /** Authoritative cache, keyed by the absolute resolved tab id. */
    private final ConcurrentHashMap<TabKey, Cached> cache = new ConcurrentHashMap<>();

    /** Auxiliary index: (session, TabRef-canonical) -> last resolved tab id. */
    private final ConcurrentHashMap<RefKey, Long> refIndex = new ConcurrentHashMap<>();

    /**
     * In-flight requests, keyed by (sessionId, TabRef-canonical) so concurrent
     * callers for the same selector share a single edge fetch.
     */
    private final ConcurrentHashMap<RefKey, Mono<PageSnapshot>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public DefaultPageSnapshotService(SnapshotEdgeClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public Mono<PageSnapshot> request(BrowserSession session, TabRef tabRef, String filter) {
        return doRequest(session, tabRef, filter, false);
    }

    @Override
    public Mono<PageSnapshot> requestFresh(BrowserSession session, TabRef tabRef, String filter) {
        return doRequest(session, tabRef, filter, true);
    }

    private Mono<PageSnapshot> doRequest(BrowserSession session, TabRef tabRef, String filter, boolean forceFresh) {
        String sessionId = session.getId();
        RefKey refKey = new RefKey(sessionId, canonical(tabRef));

        // Fast path: do we have a previously-resolved tab id for this selector,
        // and is its cached entry still usable? If so, no wire call. SKIPPED when
        // forceFresh (observe wants the live page, not a cached one) — but we
        // still join an in-flight fetch below (that fetch IS live) and still
        // repopulate the cache FRESH so the next grounding reuses this read.
        if (!forceFresh) {
            Long lastResolvedTabId = refIndex.get(refKey);
            if (lastResolvedTabId != null) {
                Cached cached = cache.get(new TabKey(sessionId, lastResolvedTabId));
                if (cached != null && isUsable(cached)) {
                    return Mono.just(cached.snapshot());
                }
            }
        }

        // Single-flight join: if another caller already kicked off the same
        // fetch, return its cached Mono. Otherwise we register ourselves first.
        Mono<PageSnapshot> existing = inFlight.get(refKey);
        if (existing != null) {
            return existing;
        }

        // Build the fetch Mono. We use Mono.defer so each subscription drives
        // exactly one edge-client call AT MOST — combined with .cache() the
        // result is replayed to any number of concurrent subscribers.
        //
        // flatMap on the action-stream-free request() path is fine: it chains
        // a single downstream Mono off the edge client's response (cache
        // population + return) and does NOT fan out parallel work. The Wave 4
        // audit grep flags every flatMap so the reviewer can verify this.
        Mono<PageSnapshot> fetch = Mono.defer(() -> client.request(session, tabRef, filter))
                .flatMap(snap -> {
                    // flatMap reason: chain a synchronous cache-population step
                    // before publishing the snapshot to subscribers. No fan-out,
                    // no parallel dispatch — single Mono in, single Mono out.
                    long resolvedTabId = snap.resolvedTabId();
                    // A blank tree is a DEGENERATE snapshot — the tab was blank,
                    // closed, or not yet laid out when the extractor ran. Cache it
                    // as STALE (never FRESH) so the very NEXT observe REFETCHES
                    // rather than replaying the empty tree for MAX_AGE. Without this
                    // a single transient empty snapshot sticks for 30s, turning one
                    // unlucky read into a half-minute of "empty page" (the
                    // intermittent "时好时坏" the user hit). A non-degenerate snapshot
                    // is cached FRESH as before.
                    SnapshotState state = snap.tree().isBlank()
                            ? SnapshotState.STALE
                            : SnapshotState.FRESH;
                    cache.put(
                            new TabKey(sessionId, resolvedTabId),
                            new Cached(snap, state, clock.instant()));
                    refIndex.put(refKey, resolvedTabId);
                    return Mono.just(snap);
                })
                .doFinally(sig -> inFlight.remove(refKey))
                .cache();

        Mono<PageSnapshot> winner = inFlight.putIfAbsent(refKey, fetch);
        return winner != null ? winner : fetch;
    }

    @Override
    public void onActionSuccess(String sessionId, long resolvedTabId, ActionKind kind) {
        TabKey key = new TabKey(sessionId, resolvedTabId);
        cache.computeIfPresent(key, (k, c) -> switch (kind) {
            case NAVIGATE, DOUYIN_SEARCH, DOUYIN_OPEN_VIDEO -> c.withState(SnapshotState.STALE);
            case CLICK, TYPE, PRESS_KEY, SCROLL, SCROLL_REGION, CLICK_PROFILE_ACTION, TYPE_DM_DRAFT, DOUYIN_UI -> c.withState(SnapshotState.SUSPECT);
            case OPEN_AUTHOR_FROM_COMMENT -> c.withState(SnapshotState.STALE);
            case CLOSE_TAB -> null;
            case MOVE_MOUSE, WAIT, REGISTER_REGION, DETECT_REGION, EXTRACT_REGION, DOUYIN_COMMENT_NETWORK -> c; // no transition — DOM not mutated
        });
    }

    @Override
    public void onPageEvent(String sessionId, long resolvedTabId, PageEvent event) {
        TabKey key = new TabKey(sessionId, resolvedTabId);
        switch (event) {
            case NAVIGATED -> cache.computeIfPresent(key, (k, c) -> c.withState(SnapshotState.STALE));
            case TAB_CLOSED -> {
                cache.remove(key);
                // Drop any selector → tabId mapping that points at this tab so
                // the next request for the same selector forces a fresh fetch
                // (the SW will resolve to a different tab now).
                refIndex.entrySet().removeIf(e ->
                        e.getKey().sessionId().equals(sessionId)
                                && e.getValue() == resolvedTabId);
            }
        }
    }

    @Override
    public void invalidate(String sessionId, long resolvedTabId) {
        TabKey key = new TabKey(sessionId, resolvedTabId);
        cache.computeIfPresent(key, (k, c) -> c.withState(SnapshotState.STALE));
    }

    /**
     * A cached snapshot is usable iff its age is within {@link #MAX_AGE} AND
     * its state is FRESH or SUSPECT. STALE always forces a refetch.
     */
    private boolean isUsable(Cached c) {
        if (Duration.between(c.cachedAt(), clock.instant()).compareTo(MAX_AGE) > 0) {
            return false;
        }
        return switch (c.state()) {
            case FRESH, SUSPECT -> true;
            case STALE -> false;
        };
    }

    /**
     * Canonical wire form for a TabRef so we can compose it into a Map key.
     * Matches the wire shape the SW sees (string for symbolic, integer-as-string
     * for explicit). Two TabRefs that resolve via the same SW logic share a key.
     */
    private static String canonical(TabRef ref) {
        return switch (ref) {
            case TabRef.Main m -> "main";
            case TabRef.Active a -> "active";
            case TabRef.Explicit e -> Long.toString(e.tabId());
        };
    }

    /** Authoritative cache key: per session, per absolute resolved tab id. */
    private record TabKey(String sessionId, long resolvedTabId) {}

    /** Auxiliary index key: per session, per wire-form selector. */
    private record RefKey(String sessionId, String tabRefCanonical) {}

    /** Cache value. Immutable; copy-on-state-change via {@link #withState}. */
    private record Cached(PageSnapshot snapshot, SnapshotState state, Instant cachedAt) {
        Cached withState(SnapshotState newState) {
            return new Cached(snapshot, newState, cachedAt);
        }
    }
}
