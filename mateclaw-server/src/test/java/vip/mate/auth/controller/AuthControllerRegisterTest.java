package vip.mate.auth.controller;

import org.junit.jupiter.api.Test;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.RegisterRequest;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;
import vip.mate.auth.sms.VerificationCodeService;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerRegisterTest {

    @Test
    void registerDelegatesToAuthService() {
        AuthService authService = mock(AuthService.class);
        AccountEntitlementService entitlementService = mock(AccountEntitlementService.class);
        AuthController controller = new AuthController(authService, entitlementService, mock(VerificationCodeService.class));
        RegisterRequest request = new RegisterRequest();
        LoginResponse response = new LoginResponse(
                7L, "token", "13800138000", "13800138000", "user",
                LocalDateTime.now().plusDays(30), false, 10L);
        when(authService.register(request)).thenReturn(response);

        var result = controller.register(request);

        assertEquals(response, result.getData());
        verify(authService).register(request);
    }
}
