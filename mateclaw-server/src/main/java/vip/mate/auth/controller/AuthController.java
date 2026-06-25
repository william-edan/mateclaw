package vip.mate.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.auth.model.AccountStatusResponse;
import vip.mate.auth.model.LoginRequest;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.RegisterRequest;
import vip.mate.auth.model.SendCodeRequest;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AccountEntitlementService;
import vip.mate.auth.service.AuthService;
import vip.mate.auth.sms.VerificationCodeService;
import vip.mate.auth.support.ClientIp;
import vip.mate.auth.support.PhoneNumbers;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

import java.util.List;

/**
 * 认证接口
 *
 * @author MateClaw Team
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AccountEntitlementService accountEntitlementService;
    private final VerificationCodeService verificationCodeService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public R<LoginResponse> login(@RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @Operation(summary = "手机号注册")
    @PostMapping("/register")
    public R<LoginResponse> register(@RequestBody RegisterRequest request) {
        return R.ok(authService.register(request));
    }

    @Operation(summary = "发送注册验证码")
    @PostMapping("/send-register-code")
    public R<Void> sendRegisterCode(@RequestBody SendCodeRequest request, HttpServletRequest httpRequest) {
        String phone = PhoneNumbers.normalize(request != null ? request.getPhone() : null);
        if (!PhoneNumbers.isValid(phone)) {
            throw new MateClawException("err.auth.invalid_phone", 400, "手机号格式不正确");
        }
        if (authService.isPhoneRegistered(phone)) {
            throw new MateClawException("err.auth.username_exists", 409, "该手机号已注册，请直接登录");
        }
        verificationCodeService.sendRegisterCode(phone, ClientIp.from(httpRequest));
        return R.ok();
    }

    @Operation(summary = "获取当前账号状态")
    @GetMapping("/me")
    public R<AccountStatusResponse> me(Authentication auth) {
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", 404, "用户不存在");
        }
        return R.ok(AccountStatusResponse.from(user, accountEntitlementService.isExpired(user)));
    }

    @Operation(summary = "获取用户列表")
    @GetMapping("/users")
    @RequireGlobalAdmin
    public R<List<UserEntity>> listUsers() {
        return R.ok(authService.listUsers());
    }

    @Operation(summary = "创建用户")
    @PostMapping("/users")
    @RequireGlobalAdmin
    public R<UserEntity> createUser(@RequestBody UserEntity user) {
        return R.ok(authService.createUser(user));
    }

    @Operation(summary = "修改密码")
    @PutMapping("/users/{id}/password")
    public R<Void> changePassword(
            @PathVariable Long id,
            @RequestParam String oldPassword,
            @RequestParam String newPassword,
            Authentication auth) {
        // Resolve user from the JWT principal — the {id} path segment is
        // informational. A user may only change their own password.
        UserEntity me = authService.findByUsername(auth.getName());
        if (me == null) {
            throw new MateClawException("err.auth.user_not_found", "用户不存在");
        }
        authService.changePassword(me.getId(), oldPassword, newPassword);
        return R.ok();
    }
}
