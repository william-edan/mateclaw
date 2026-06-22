package vip.mate.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;
import vip.mate.auth.sms.VerificationCodeService;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerAccountStatusTest {

    @Test
    void meReturnsCurrentAccountStatus() {
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        AuthController controller = new AuthController(authService, entitlementService, mock(VerificationCodeService.class));
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(3);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setRole("user");
        user.setExpiresAt(expiresAt);
        when(authService.findByUsername("alice")).thenReturn(user);
        when(entitlementService.isExpired(user)).thenReturn(true);

        var result = controller.me(new UsernamePasswordAuthenticationToken("alice", "token"));

        assertEquals(42L, result.getData().id());
        assertEquals("alice", result.getData().username());
        assertEquals("Alice", result.getData().nickname());
        assertEquals("user", result.getData().role());
        assertEquals(expiresAt, result.getData().expiresAt());
        assertTrue(result.getData().expired());
        verify(authService).findByUsername("alice");
        verify(entitlementService).isExpired(user);
    }

    @Test
    void meThrowsNotFoundWhenCurrentUserMissing() {
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        AuthController controller = new AuthController(authService, entitlementService, mock(VerificationCodeService.class));
        when(authService.findByUsername("missing")).thenReturn(null);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller.me(new UsernamePasswordAuthenticationToken("missing", "token")));

        assertEquals("err.auth.user_not_found", ex.getMsgKey());
        assertEquals(404, ex.getCode());
        assertEquals("用户不存在", ex.getMessage());
    }
}
