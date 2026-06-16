package vip.mate.browser.desktop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desktop bridge provisioner.
 *
 * <p>When the server is launched by the desktop shell (env
 * {@code MATECLAW_DESKTOP=true}), it mints a long-lived browser-scoped
 * Personal Access Token for the configured user and drops it into
 * {@code <user.home>/.mateclaw/bridge.yaml} so the Native-Messaging bridge can
 * authenticate the {@code /api/v1/browser/edge} WebSocket handshake without any
 * manual pairing step.
 *
 * <p>Shared contract — the YAML is consumed by
 * {@code mateclaw-browser-bridge/src/internal/config/config.ts}, so the keys are
 * snake_case and must stay exactly {@code control_plane_url} and
 * {@code auth_token}.
 *
 * <p>Idempotent: on every boot we re-read {@code bridge.yaml}; if it already
 * carries a non-blank {@code auth_token} that still validates as an active PAT
 * we leave it untouched. Otherwise we mint a fresh token (far-future expiry) and
 * rewrite the file atomically.
 *
 * <p>Best-effort by design — any failure (missing user, IO error, …) is logged
 * and swallowed so a desktop provisioning hiccup never blocks server startup.
 *
 * <p>{@code @Order(300)} runs after {@code DatabaseBootstrapRunner}(1) /
 * {@code McpServerBootstrapRunner}(200) so the {@code mate_user} +
 * {@code mate_personal_access_token} tables are ready.
 */
@Slf4j
@Component
@Order(300)
@RequiredArgsConstructor
public class DesktopBridgeProvisioner implements ApplicationRunner {

    /** Env flag the desktop launcher injects to request auto-provisioning. */
    static final String DESKTOP_ENV = "MATECLAW_DESKTOP";

    /** PAT name stamped on the desktop-issued token. */
    static final String TOKEN_NAME = "browser-extension/desktop";

    /** Scope the edge WebSocket handshake expects (shared contract). */
    static final String EDGE_SCOPE = "browser:edge";

    /** Effectively-permanent lifetime for the desktop bridge token (~10y). */
    static final long TOKEN_TTL_DAYS = 3650;

    /** Edge WS path appended to {@code ws://localhost:<port>} (shared contract). */
    static final String EDGE_WS_PATH = "/api/v1/browser/edge";

    /** YAML key — must match config.ts {@code control_plane_url}. */
    static final String KEY_CONTROL_PLANE_URL = "control_plane_url";

    /** YAML key — must match config.ts {@code auth_token}. */
    static final String KEY_AUTH_TOKEN = "auth_token";

    private final AuthService authService;
    private final PersonalAccessTokenService patService;

    @Value("${server.port:18088}")
    private int serverPort;

    @Value("${mate.desktop.bridge-username:admin}")
    private String bridgeUsername;

    @Override
    public void run(ApplicationArguments args) {
        if (!isDesktopMode()) {
            return;
        }
        try {
            provision();
        } catch (Exception e) {
            // Never block startup on a provisioning failure.
            log.warn("[desktop-bridge] auto-provisioning failed (non-fatal): {}", e.getMessage(), e);
        }
    }

    /**
     * Desktop mode is on when {@code MATECLAW_DESKTOP=true}. Kept as a tiny
     * package-private seam so a unit test can drive the runner without touching
     * the real process environment.
     */
    boolean isDesktopMode() {
        return "true".equalsIgnoreCase(trimOrNull(System.getenv(DESKTOP_ENV)));
    }

    /** Core flow; package-private so tests can invoke it directly. */
    void provision() {
        UserEntity user = authService.findByUsername(bridgeUsername);
        if (user == null || user.getId() == null) {
            log.warn("[desktop-bridge] user '{}' not found — skipping bridge.yaml provisioning. "
                    + "Set mate.desktop.bridge-username to an existing account.", bridgeUsername);
            return;
        }

        Path bridgeFile = resolveBridgeFile();

        if (hasValidToken(bridgeFile)) {
            log.info("[desktop-bridge] {} already has a valid token — skipping re-issue", bridgeFile);
            return;
        }

        PersonalAccessTokenService.CreatedToken created = patService.create(
                user.getId(),
                TOKEN_NAME,
                EDGE_SCOPE,
                LocalDateTime.now().plusDays(TOKEN_TTL_DAYS));

        String controlPlaneUrl = "ws://localhost:" + serverPort + EDGE_WS_PATH;
        writeBridgeYaml(bridgeFile, controlPlaneUrl, created.plaintext());

        log.info("[desktop-bridge] issued PAT id={} for user '{}' (id={}) and wrote {} (control_plane_url={})",
                created.id(), bridgeUsername, user.getId(), bridgeFile, controlPlaneUrl);
    }

    /**
     * Idempotency check — true when {@code bridge.yaml} already carries a
     * non-blank {@code auth_token} that the PAT service still validates as
     * active. A read/parse failure is treated as "no valid token" so we
     * re-provision rather than crash.
     */
    boolean hasValidToken(Path bridgeFile) {
        String existing = readAuthToken(bridgeFile);
        if (existing == null) {
            return false;
        }
        return patService.findActiveByPlaintext(existing).isPresent();
    }

    /**
     * Read just the {@code auth_token} value out of an existing
     * {@code bridge.yaml}. Returns null when the file is missing, unreadable,
     * not a YAML mapping, or the key is absent/blank.
     */
    String readAuthToken(Path bridgeFile) {
        if (!Files.isReadable(bridgeFile)) {
            return null;
        }
        try {
            String raw = Files.readString(bridgeFile, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return null;
            }
            Object parsed = new Yaml().load(raw);
            if (parsed instanceof Map<?, ?> map) {
                Object token = map.get(KEY_AUTH_TOKEN);
                return token == null ? null : trimOrNull(token.toString());
            }
            return null;
        } catch (Exception e) {
            log.debug("[desktop-bridge] could not parse {} ({}); will re-provision", bridgeFile, e.getMessage());
            return null;
        }
    }

    /**
     * Write {@code bridge.yaml} atomically (temp file in the same directory +
     * rename) so a concurrently-reading bridge never sees a half-written file.
     * The {@code .mateclaw} directory is created if absent.
     */
    void writeBridgeYaml(Path bridgeFile, String controlPlaneUrl, String authToken) {
        try {
            Path parent = bridgeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // LinkedHashMap → stable key order; SnakeYAML handles quoting/escaping
            // so the value round-trips through config.ts's `yaml` parser.
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put(KEY_CONTROL_PLANE_URL, controlPlaneUrl);
            doc.put(KEY_AUTH_TOKEN, authToken);

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            byte[] payload = new Yaml(options).dump(doc).getBytes(StandardCharsets.UTF_8);

            Path tmp = Files.createTempFile(
                    parent != null ? parent : bridgeFile.toAbsolutePath().getParent(),
                    ".bridge-", ".tmp");
            try {
                Files.write(tmp, payload);
                try {
                    Files.move(tmp, bridgeFile,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    // Some filesystems don't support atomic cross-FS moves.
                    Files.move(tmp, bridgeFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            log.warn("[desktop-bridge] failed to write {}: {}", bridgeFile, e.getMessage());
        }
    }

    /** {@code <user.home>/.mateclaw/bridge.yaml} (shared contract path). */
    Path resolveBridgeFile() {
        return Path.of(System.getProperty("user.home"), ".mateclaw", "bridge.yaml");
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
