package vip.mate.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import vip.mate.auth.model.SendCodeRequest;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;
import vip.mate.auth.sms.VerificationCodeService;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerSendCodeTest {

    private AuthController controller(AuthService authService, VerificationCodeService vcs) {
        return new AuthController(authService, mock(AccountEntitlementService.class), vcs);
    }

    @Test
    void sendsCodeForValidUnregisteredPhone() {
        AuthService authService = mock(AuthService.class);
        VerificationCodeService vcs = mock(VerificationCodeService.class);
        when(authService.isPhoneRegistered("13800138000")).thenReturn(false);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("9.9.9.9");

        SendCodeRequest body = new SendCodeRequest();
        body.setPhone(" 138 0013-8000 ");
        controller(authService, vcs).sendRegisterCode(body, req);

        verify(vcs).sendRegisterCode(eq("13800138000"), eq("9.9.9.9"));
    }

    @Test
    void rejectsInvalidPhone() {
        AuthService authService = mock(AuthService.class);
        VerificationCodeService vcs = mock(VerificationCodeService.class);
        SendCodeRequest body = new SendCodeRequest();
        body.setPhone("abc");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller(authService, vcs).sendRegisterCode(body, mock(HttpServletRequest.class)));
        assertEquals(400, ex.getCode());
    }

    @Test
    void rejectsAlreadyRegistered() {
        AuthService authService = mock(AuthService.class);
        VerificationCodeService vcs = mock(VerificationCodeService.class);
        when(authService.isPhoneRegistered("13800138000")).thenReturn(true);
        SendCodeRequest body = new SendCodeRequest();
        body.setPhone("13800138000");

        MateClawException ex = assertThrows(MateClawException.class,
                () -> controller(authService, vcs).sendRegisterCode(body, mock(HttpServletRequest.class)));
        assertEquals(409, ex.getCode());
    }
}
