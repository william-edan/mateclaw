package vip.mate.browser.edge.auth;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Authenticated edge principal.
 *
 * <p>{@code subject} carries either the JWT subject (username) or the PAT
 * owner's {@code userId.toString()} — see {@link EdgeAuthInterceptor} for
 * how each is produced. Long-typed identity is introduced in Phase 4.
 *
 * <p>{@code scheme} is "jwt" or "pat" for observability/audit.
 *
 * <p>{@code aliasKeys} (契约4) holds every subject-string this user is also
 * reachable under — for a PAT login the owner's username; for a JWT login the
 * owner's {@code userId} string — so the registry can register the session under
 * both, letting web (username) and desktop PAT (userId) routes hit the same
 * browser. {@code subject} is always implicitly included even if absent from
 * this set. Empty (never {@code null}) when the counterpart identity could not
 * be resolved.
 */
public record EdgePrincipal(String subject, String scheme, Set<String> aliasKeys) {

    /** Backward-compatible: no extra aliases (only {@code subject} is keyed). */
    public EdgePrincipal(String subject, String scheme) {
        this(subject, scheme, Set.of());
    }

    /**
     * @return {@code subject} plus every non-blank alias, de-duplicated and
     *         order-stable (subject first). Always non-empty.
     */
    public Set<String> allKeys() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(subject);
        if (aliasKeys != null) {
            for (String k : aliasKeys) {
                if (k != null && !k.isBlank()) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    /** Convenience factory: build a principal with a single resolved alias. */
    public static EdgePrincipal of(String subject, String scheme, String alias) {
        return new EdgePrincipal(subject, scheme,
                alias == null || alias.isBlank() ? Set.of() : Set.of(alias));
    }
}
