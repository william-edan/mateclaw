package vip.mate.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.auth.model.LoginRequest;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.RegisterRequest;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 认证服务（JWT）
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String FIXED_REGISTER_CODE = "888888";
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?\\d{6,20}$");

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final WorkspaceService workspaceService;
    private final AccountEntitlementService accountEntitlementService;

    @Value("${mateclaw.jwt.secret:MateClaw-Secret-Key-2024-Very-Long-String}")
    private String jwtSecret;

    @Value("${mateclaw.jwt.expiration:86400000}")
    private long jwtExpiration;

    @Value("${mateclaw.jwt.renewal-threshold:7200000}")
    private long renewalThreshold;

    /**
     * 登录
     */
    public LoginResponse login(LoginRequest request) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.getUsername())
                .eq(UserEntity::getEnabled, true));

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new MateClawException("err.auth.invalid_credentials", 401, "用户名或密码错误");
        }

        String token = generateToken(user);
        return loginResponse(user, token, resolveCurrentWorkspaceId(user.getId()));
    }

    /**
     * 手机号注册。验证码临时固定为 888888，手机号作为 username 保存。
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        String phone = normalizePhone(request != null ? request.getPhone() : null);
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new MateClawException("err.auth.invalid_phone", 400, "手机号格式不正确");
        }
        if (!FIXED_REGISTER_CODE.equals(request.getCode())) {
            throw new MateClawException("err.auth.invalid_verification_code", 400, "验证码错误");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new MateClawException("err.auth.password_required", 400, "Password is required");
        }

        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, phone));
        if (count > 0) {
            throw new MateClawException("err.auth.username_exists", 409, "手机号已注册: " + phone);
        }

        UserEntity user = new UserEntity();
        user.setUsername(phone);
        user.setPassword(passwordEncoder.encode(request.getPassword().trim()));
        user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                ? phone
                : request.getNickname().trim());
        user.setRole("user");
        user.setEnabled(true);
        user.setExpiresAt(LocalDateTime.now().plusDays(30));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new MateClawException("err.auth.username_exists", 409, "手机号已注册: " + phone);
        }

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setName(user.getNickname() + " Workspace");
        WorkspaceEntity createdWorkspace = workspaceService.create(workspace, user.getId());

        String token = generateToken(user);
        return loginResponse(user, token, createdWorkspace != null ? createdWorkspace.getId() : null);
    }

    private LoginResponse loginResponse(UserEntity user, String token, Long currentWorkspaceId) {
        return new LoginResponse(
                user.getId(),
                token,
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                user.getExpiresAt(),
                accountEntitlementService.isExpired(user),
                currentWorkspaceId);
    }

    private Long resolveCurrentWorkspaceId(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            List<WorkspaceEntity> workspaces = workspaceService.listByUserId(userId);
            return workspaces == null || workspaces.isEmpty() ? null : workspaces.get(0).getId();
        } catch (Exception e) {
            log.warn("[AuthService] Failed to resolve current workspace for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 获取用户列表（管理员）
     */
    public List<UserEntity> listUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getEnabled, true));
    }

    /**
     * 创建用户
     */
    public UserEntity createUser(UserEntity user) {
        // 检查用户名是否已存在
        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, user.getUsername()));
        if (count > 0) {
            throw new MateClawException("err.auth.username_exists", "用户名已存在: " + user.getUsername());
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new MateClawException("err.auth.password_required", "Password is required");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword().trim()));
        user.setEnabled(true);
        if (user.getRole() == null) {
            user.setRole("user");
        }
        userMapper.insert(user);
        user.setPassword(null);
        return user;
    }

    /**
     * Reset password (admin operation — no old password required).
     * Used when an admin wants to set/reset a member's password.
     */
    public void resetPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new MateClawException("err.auth.password_required", "Password is required");
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found", "用户不存在");
        }
        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        userMapper.updateById(user);
    }

    /**
     * 修改密码
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        verifyCurrentUserPassword(userId, oldPassword);
        UserEntity user = userMapper.selectById(userId);
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    /**
     * Step-up authentication: confirms that {@code rawPassword} matches the
     * user's currently stored password without changing anything.
     * <p>
     * Used by sensitive operations that require re-confirmation of identity
     * (e.g. creating a workspace-wide all-tool auto-approve grant). Throws
     * the same {@link MateClawException} keys as {@link #changePassword} so
     * the user-facing error message stays consistent.
     *
     * @throws MateClawException {@code err.auth.user_not_found} when the user
     *         doesn't exist, or {@code err.auth.wrong_password} when the
     *         password doesn't match.
     */
    public void verifyCurrentUserPassword(Long userId, String rawPassword) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            // 404: target user no longer exists; surfacing as 401 would mask the cause.
            throw new MateClawException("err.auth.user_not_found", 404, "用户不存在");
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            // 403, not 401: 401 would trigger the global http interceptor's
            // handleAuthFailure() and log the user out, but this is a step-up
            // re-confirmation (token is still valid). Falling through to the
            // default 500 looks like a server fault on the client; 403 cleanly
            // communicates "valid session, wrong second-factor".
            throw new MateClawException("err.auth.wrong_password", 403, "原密码错误");
        }
    }

    /**
     * 解析 Token 获取用户名
     */
    public String parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 Token 获取完整 Claims（含过期时间）
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断 Token 是否接近过期（剩余有效期 < renewalThreshold）
     */
    public boolean isNearExpiry(Claims claims) {
        if (claims == null || claims.getExpiration() == null) {
            return false;
        }
        long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
        return remaining > 0 && remaining < renewalThreshold;
    }

    /**
     * 根据用户名续签 Token
     */
    public String renewToken(String username) {
        UserEntity user = findByUsername(username);
        if (user != null && Boolean.TRUE.equals(user.getEnabled())) {
            return generateToken(user);
        }
        return null;
    }

    /**
     * 根据用户名查询用户
     */
    public UserEntity findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
    }

    /**
     * 根据 ID 查询用户
     */
    public UserEntity findById(Long userId) {
        return userMapper.selectById(userId);
    }

    private String generateToken(UserEntity user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role", user.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSignKey())
                .compact();
    }

    private SecretKey getSignKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        // 确保密钥长度至少 32 字节（HMAC-SHA256）
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.trim().replaceAll("[\\s-]", "");
    }
}
