package vip.mate.browser.edge.auth;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates JWT or PAT on the /api/v1/browser/edge WebSocket handshake.
 * On success stashes {@link EdgePrincipal} under {@code EDGE_PRINCIPAL}.
 *
 * <p>Two token transports are accepted:
 * <ol>
 *   <li>{@code Authorization: Bearer <t>} — used by the Native-Messaging bridge
 *       (server-to-server), which CAN set request headers.</li>
 *   <li>{@code Sec-WebSocket-Protocol: mateclaw.edge.v1, bearer.<t>} — used by the
 *       browser extension service worker (Phase 3.1), which CANNOT set
 *       {@code Authorization} on a WebSocket. The token rides as a declared
 *       subprotocol; the server echoes only {@code mateclaw.edge.v1} back
 *       (see {@code EdgeWebSocketHandler#getSubProtocols()}), never the bearer.</li>
 * </ol>
 * Either way the extracted token runs through the same JWT-then-PAT cascade.
 *
 * <p>Handshake-time auth failures return HTTP 401 (per edge-protocol spec).
 * Post-upgrade auth loss (token revoked mid-session, WS close 4401) is NOT
 * implemented in Phase 1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeAuthInterceptor implements HandshakeInterceptor {

    public static final String ATTR_PRINCIPAL = "EDGE_PRINCIPAL";

    /** Subprotocol entry prefix carrying the auth token in direct (browser) mode. */
    static final String BEARER_SUBPROTOCOL_PREFIX = "bearer.";

    private final AuthService authService;
    private final PersonalAccessTokenService patService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // JWT first — cheaper (signature verify, no DB hit).
        Claims claims = authService.parseClaims(token);
        if (claims != null && claims.getSubject() != null) {
            // 契约4: subject is the username; alias the userId so a desktop PAT
            // session (keyed by userId) and this web session resolve the same
            // browser. The userId rides in the JWT as a claim — no DB hit needed.
            String userIdAlias = jwtUserIdAlias(claims);
            attributes.put(ATTR_PRINCIPAL,
                    EdgePrincipal.of(claims.getSubject(), "jwt", userIdAlias));
            return true;
        }

        // PAT fallback — DB lookup.
        // TODO(phase-3): once per-scope enforcement RFC lands, also check
        //   entity.getScopes().contains("browser:edge") and return 403 if
        //   the token is active but lacks the scope.
        Optional<PersonalAccessTokenEntity> pat = patService.findActiveByPlaintext(token);
        if (pat.isPresent()) {
            Long userId = pat.get().getUserId();
            // 契约4: subject is the userId string; alias the username so a web
            // (username-keyed) session and this PAT session cross-hit.
            String usernameAlias = patUsernameAlias(userId);
            attributes.put(ATTR_PRINCIPAL,
                    EdgePrincipal.of(userId.toString(), "pat", usernameAlias));
            return true;
        }

        log.debug("[edge] rejected handshake — bad/expired token");
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    /**
     * Best-effort: read the {@code userId} claim from a JWT so it can be aliased
     * alongside the username subject (契约4). Returns {@code null} when the claim
     * is absent or unparseable — alias resolution is purely additive, so a miss
     * just means this session is keyed by username only (today's behaviour).
     */
    private String jwtUserIdAlias(Claims claims) {
        try {
            Object userId = claims.get("userId");
            if (userId == null) {
                return null;
            }
            // The claim may surface as Integer/Long/String depending on signer.
            String s = String.valueOf(userId).trim();
            return s.isEmpty() ? null : s;
        } catch (RuntimeException e) {
            log.debug("[edge] could not read userId claim for alias: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort: resolve the owning username for a PAT's userId so it can be
     * aliased alongside the userId subject (契约4). One DB lookup on the PAT path
     * (already a DB-bound path). Returns {@code null} on any miss/error — alias
     * resolution is additive and must never fail the handshake.
     */
    private String patUsernameAlias(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            var user = authService.findById(userId);
            return user == null ? null : user.getUsername();
        } catch (RuntimeException e) {
            log.debug("[edge] could not resolve username for PAT userId={} alias: {}",
                    userId, e.getMessage());
            return null;
        }
    }

    /**
     * Extract the auth token from either transport. The {@code Authorization}
     * header wins (NH bridge path); otherwise the {@code Sec-WebSocket-Protocol}
     * header is parsed for a {@code bearer.<token>} entry (direct browser path).
     * Returns {@code null} when neither yields a usable token.
     */
    private String resolveToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String t = auth.substring("Bearer ".length()).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return extractBearerFromSubprotocol(
                request.getHeaders().get("Sec-WebSocket-Protocol"));
    }

    /**
     * Parse the {@code Sec-WebSocket-Protocol} header value(s) for the
     * {@code bearer.<token>} entry and return the token with the
     * {@code bearer.} prefix stripped.
     *
     * <p>The header may arrive as multiple values (the servlet container can
     * surface a repeated header as a {@link List}), and each value is itself a
     * comma-separated list of offered subprotocols, e.g.
     * {@code "mateclaw.edge.v1, bearer.mc_xxxxx"}. We join all values, split on
     * {@code ','}, trim, and find the entry starting with {@code bearer.}.
     *
     * <p>The prefix is removed with {@link String#substring(int)} (NOT a
     * dot-split) because JWTs contain dots — {@code bearer.eyJ.aaa.bbb} must
     * yield {@code eyJ.aaa.bbb}. PAT plaintext ({@code mc_<base64url>}) and JWTs
     * are both comma-free, so comma-splitting the list is safe.
     *
     * <p>Package-private + static so it can be unit-tested without the servlet
     * request plumbing.
     *
     * @return the extracted token, or {@code null} if absent/empty
     */
    static String extractBearerFromSubprotocol(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) {
            return null;
        }
        for (String headerValue : headerValues) {
            if (headerValue == null) {
                continue;
            }
            for (String entry : headerValue.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.startsWith(BEARER_SUBPROTOCOL_PREFIX)) {
                    String token = trimmed.substring(BEARER_SUBPROTOCOL_PREFIX.length()).trim();
                    return token.isEmpty() ? null : token;
                }
            }
        }
        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // no-op
    }
}
