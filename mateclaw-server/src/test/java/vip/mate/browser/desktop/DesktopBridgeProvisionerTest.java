package vip.mate.browser.desktop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.pat.PersonalAccessTokenEntity;
import vip.mate.auth.pat.PersonalAccessTokenService;
import vip.mate.auth.service.AuthService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link DesktopBridgeProvisioner} — drives {@code provision()}
 * directly (bypassing the {@code MATECLAW_DESKTOP} env gate) and points the
 * bridge file at a {@link TempDir} via a tiny subclass.
 *
 * <ul>
 *   <li>mints a {@code browser:edge} PAT with a far-future expiry for the
 *       configured user and writes {@code bridge.yaml} with the exact snake_case
 *       keys the bridge config.ts consumes;</li>
 *   <li>idempotent — an existing, still-active token short-circuits re-issue;</li>
 *   <li>an existing-but-invalid token triggers a fresh mint + overwrite;</li>
 *   <li>missing user is logged and skipped (no PAT, no file).</li>
 * </ul>
 */
class DesktopBridgeProvisionerTest {

    private static final long USER_ID = 77L;
    private static final String USERNAME = "admin";

    @TempDir
    Path homeDir;

    private AuthService authService;
    private PersonalAccessTokenService patService;
    private Path bridgeFile;
    private DesktopBridgeProvisioner provisioner;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        patService = mock(PersonalAccessTokenService.class);
        bridgeFile = homeDir.resolve(".mateclaw").resolve("bridge.yaml");

        // Subclass pins the bridge file into the temp dir so we never touch the
        // real <user.home>/.mateclaw.
        provisioner = new DesktopBridgeProvisioner(authService, patService) {
            @Override
            Path resolveBridgeFile() {
                return bridgeFile;
            }
        };
        ReflectionTestUtils.setField(provisioner, "serverPort", 18088);
        ReflectionTestUtils.setField(provisioner, "bridgeUsername", USERNAME);

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        when(authService.findByUsername(USERNAME)).thenReturn(user);
    }

    @Test
    void provision_mintsScopedFarFutureTokenAndWritesContractYaml() throws Exception {
        var entity = new PersonalAccessTokenEntity();
        entity.setId(555L);
        when(patService.create(eq(USER_ID), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(new PersonalAccessTokenService.CreatedToken(555L, "mc_freshtoken", entity));
        // No existing token on disk → findActiveByPlaintext never consulted, but be safe.
        when(patService.findActiveByPlaintext(anyString())).thenReturn(Optional.empty());

        LocalDateTime before = LocalDateTime.now().plusDays(DesktopBridgeProvisioner.TOKEN_TTL_DAYS).minusMinutes(1);
        provisioner.provision();
        LocalDateTime after = LocalDateTime.now().plusDays(DesktopBridgeProvisioner.TOKEN_TTL_DAYS).plusMinutes(1);

        // PAT minted with the contracted name/scope and a ~10y expiry.
        var expCaptor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(patService).create(eq(USER_ID),
                eq(DesktopBridgeProvisioner.TOKEN_NAME),
                eq(DesktopBridgeProvisioner.EDGE_SCOPE),
                expCaptor.capture());
        assertThat(expCaptor.getValue()).isAfter(before).isBefore(after);

        // bridge.yaml exists with the exact snake_case keys config.ts reads.
        assertThat(Files.exists(bridgeFile)).isTrue();
        Object parsed = new Yaml().load(Files.readString(bridgeFile, StandardCharsets.UTF_8));
        assertThat(parsed).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) parsed;
        assertThat(doc).containsEntry("control_plane_url", "ws://localhost:18088/api/v1/browser/edge");
        assertThat(doc).containsEntry("auth_token", "mc_freshtoken");
    }

    @Test
    void provision_isIdempotent_whenExistingTokenStillValid() throws Exception {
        // Pre-existing bridge.yaml with a token the service deems active.
        Files.createDirectories(bridgeFile.getParent());
        Files.writeString(bridgeFile,
                "control_plane_url: ws://localhost:18088/api/v1/browser/edge\nauth_token: mc_existing\n",
                StandardCharsets.UTF_8);
        var entity = new PersonalAccessTokenEntity();
        entity.setId(1L);
        when(patService.findActiveByPlaintext("mc_existing")).thenReturn(Optional.of(entity));

        provisioner.provision();

        // No new token minted, file left untouched.
        verify(patService, never()).create(any(), anyString(), anyString(), any());
        assertThat(Files.readString(bridgeFile, StandardCharsets.UTF_8)).contains("mc_existing");
    }

    @Test
    void provision_reissues_whenExistingTokenInvalid() throws Exception {
        // Existing file but the token no longer validates → re-issue + overwrite.
        Files.createDirectories(bridgeFile.getParent());
        Files.writeString(bridgeFile, "auth_token: mc_stale\n", StandardCharsets.UTF_8);

        var entity = new PersonalAccessTokenEntity();
        entity.setId(2L);
        when(patService.findActiveByPlaintext(anyString())).thenReturn(Optional.empty());
        when(patService.create(eq(USER_ID), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(new PersonalAccessTokenService.CreatedToken(2L, "mc_reissued", entity));

        provisioner.provision();

        verify(patService, times(1)).create(eq(USER_ID), anyString(), anyString(), any());
        assertThat(Files.readString(bridgeFile, StandardCharsets.UTF_8)).contains("mc_reissued");
    }

    @Test
    void provision_skips_whenUserMissing() {
        when(authService.findByUsername(USERNAME)).thenReturn(null);

        provisioner.provision();

        verify(patService, never()).create(any(), anyString(), anyString(), any());
        assertThat(Files.exists(bridgeFile)).isFalse();
    }
}
