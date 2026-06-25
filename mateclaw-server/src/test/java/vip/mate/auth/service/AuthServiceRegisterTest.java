package vip.mate.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.auth.model.LoginRequest;
import vip.mate.auth.model.LoginResponse;
import vip.mate.auth.model.RegisterRequest;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import vip.mate.auth.sms.VerificationCodeService;

@ExtendWith(MockitoExtension.class)
class AuthServiceRegisterTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private AccountEntitlementService entitlementService;

    @Mock
    private VerificationCodeService verificationCodeService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "jwtSecret", "MateClaw-Test-Secret-Key-For-Registration");
        ReflectionTestUtils.setField(authService, "jwtExpiration", 86400000L);
    }

    @Test
    void registerCreatesUserDedicatedWorkspaceAndReturnsToken() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(workspaceService.create(any(WorkspaceEntity.class), eq(99L))).thenAnswer(invocation -> {
            WorkspaceEntity workspace = invocation.getArgument(0);
            workspace.setId(321L);
            return workspace;
        });
        when(entitlementService.isExpired(any(UserEntity.class))).thenReturn(false);

        LoginResponse response = authService.register(request);

        assertEquals(99L, response.getId());
        assertEquals("13800138000", response.getUsername());
        assertEquals("13800138000", response.getNickname());
        assertEquals("user", response.getRole());
        assertNotNull(response.getToken());
        assertNotNull(response.getExpiresAt());
        assertFalse(response.isExpired());
        assertEquals(321L, response.getCurrentWorkspaceId());
        verify(workspaceService, never()).addMember(any(), any(), any());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(userCaptor.capture());
        UserEntity saved = userCaptor.getValue();
        assertEquals("13800138000", saved.getUsername());
        assertEquals("$2a$hash", saved.getPassword());
        assertEquals("13800138000", saved.getNickname());
        assertEquals("user", saved.getRole());
        assertTrue(saved.getEnabled());
        assertNotNull(saved.getExpiresAt());

        ArgumentCaptor<WorkspaceEntity> workspaceCaptor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceService).create(workspaceCaptor.capture(), eq(99L));
        WorkspaceEntity workspace = workspaceCaptor.getValue();
        assertTrue(workspace.getName().contains("13800138000"));
        verify(verificationCodeService).verifyAndConsume("13800138000", "123456");
    }

    @Test
    void registerSetsThirtyDayExpiry() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(workspaceService.create(any(WorkspaceEntity.class), eq(99L))).thenAnswer(invocation -> invocation.getArgument(0));
        when(entitlementService.isExpired(any(UserEntity.class))).thenReturn(false);

        LocalDateTime earliestExpiry = LocalDateTime.now().plusDays(30);

        authService.register(request);

        LocalDateTime latestExpiry = LocalDateTime.now().plusDays(30);
        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(userCaptor.capture());
        LocalDateTime expiresAt = userCaptor.getValue().getExpiresAt();
        assertNotNull(expiresAt);
        assertTrue(!expiresAt.isBefore(earliestExpiry));
        assertTrue(!expiresAt.isAfter(latestExpiry));
    }

    @Test
    void registerRejectsInvalidPhone() {
        RegisterRequest request = validRequest();
        request.setPhone("555-abc-1212");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.invalid_phone", ex.getMsgKey());
        verifyNoInteractions(userMapper, passwordEncoder, workspaceService, verificationCodeService);
    }

    @Test
    void registerNormalizesPhoneBeforePersistenceAndResponse() {
        RegisterRequest request = validRequest();
        request.setPhone(" 138 0013-8000 ");
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));
        when(workspaceService.create(any(WorkspaceEntity.class), eq(99L))).thenAnswer(invocation -> invocation.getArgument(0));
        when(entitlementService.isExpired(any(UserEntity.class))).thenReturn(false);

        LoginResponse response = authService.register(request);

        assertEquals("13800138000", response.getUsername());
        assertEquals("13800138000", response.getNickname());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(userCaptor.capture());
        UserEntity saved = userCaptor.getValue();
        assertEquals("13800138000", saved.getUsername());
        assertEquals("13800138000", saved.getNickname());
        verify(workspaceService).create(any(WorkspaceEntity.class), eq(99L));
        verify(workspaceService, never()).addMember(any(), any(), any());
    }

    @Test
    void registerRejectsDuplicatePhoneWithoutConsumingCode() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.username_exists", ex.getMsgKey());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verify(verificationCodeService, never()).verifyAndConsume(anyString(), anyString());
        verifyNoInteractions(workspaceService);
    }

    @Test
    void registerMapsDuplicateKeyRaceToUsernameExists() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("pass1234")).thenReturn("$2a$hash");
        when(userMapper.insert(any(UserEntity.class))).thenThrow(new DuplicateKeyException("duplicate username"));

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.username_exists", ex.getMsgKey());
        verifyNoInteractions(workspaceService);
    }

    @Test
    void registerRejectsBlankPassword() {
        RegisterRequest request = validRequest();
        request.setPassword(" ");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.password_required", ex.getMsgKey());
        verifyNoInteractions(userMapper, passwordEncoder, workspaceService, verificationCodeService);
    }

    @Test
    void registerRejectsShortPassword() {
        RegisterRequest request = validRequest();
        request.setPassword("123");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.password_too_short", ex.getMsgKey());
        verifyNoInteractions(userMapper, passwordEncoder, workspaceService, verificationCodeService);
    }

    @Test
    void registerRejectsMissingCode() {
        RegisterRequest request = validRequest();
        request.setCode("  ");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.verification_code_required", ex.getMsgKey());
        verifyNoInteractions(userMapper, passwordEncoder, workspaceService, verificationCodeService);
    }

    @Test
    void registerRejectsWrongCode() {
        RegisterRequest request = validRequest();
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        doThrow(new MateClawException("err.auth.invalid_verification_code", 400, "验证码错误"))
                .when(verificationCodeService).verifyAndConsume("13800138000", "123456");

        MateClawException ex = assertThrows(MateClawException.class, () -> authService.register(request));

        assertEquals("err.auth.invalid_verification_code", ex.getMsgKey());
        verify(userMapper, never()).insert(any(UserEntity.class));
        verifyNoInteractions(workspaceService);
    }

    @Test
    void loginAllowsExpiredUserAndReturnsExpiryFieldsAndCurrentWorkspace() {
        LocalDateTime expiresAt = LocalDateTime.now().minusDays(1);
        UserEntity user = new UserEntity();
        user.setId(99L);
        user.setUsername("13800138000");
        user.setPassword("$2a$hash");
        user.setNickname("Expired User");
        user.setRole("user");
        user.setEnabled(true);
        user.setExpiresAt(expiresAt);
        LoginRequest request = new LoginRequest();
        request.setUsername("13800138000");
        request.setPassword("pass1234");
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(passwordEncoder.matches("pass1234", "$2a$hash")).thenReturn(true);
        when(entitlementService.isExpired(user)).thenReturn(true);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(321L);
        when(workspaceService.listByUserId(99L)).thenReturn(List.of(workspace));

        LoginResponse response = authService.login(request);

        assertEquals(99L, response.getId());
        assertEquals("13800138000", response.getUsername());
        assertEquals("Expired User", response.getNickname());
        assertEquals("user", response.getRole());
        assertNotNull(response.getToken());
        assertEquals(expiresAt, response.getExpiresAt());
        assertTrue(response.isExpired());
        assertEquals(321L, response.getCurrentWorkspaceId());
        verify(entitlementService).isExpired(user);
        verify(entitlementService, never()).requireActive(any(UserEntity.class));
    }

    private RegisterRequest validRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setPhone("13800138000");
        request.setPassword("pass1234");
        request.setCode("123456");
        return request;
    }
}
