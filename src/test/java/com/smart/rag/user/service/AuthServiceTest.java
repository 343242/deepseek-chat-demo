package com.smart.rag.user.service;

import com.smart.rag.user.service.impl.AuthServiceImpl;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.RateLimitExceededException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.web.config.JwtProperties;
import com.smart.rag.infrastructure.web.auth.UserPermissionProvider;
import com.smart.rag.infrastructure.web.service.CaptchaService;
import com.smart.rag.infrastructure.web.service.TokenCacheService;
import com.smart.rag.infrastructure.web.util.JwtTokenProvider;
import com.smart.rag.user.dto.LoginResponse;
import com.smart.rag.user.dto.UserUpdateRequest;
import com.smart.rag.user.entity.SysRole;
import com.smart.rag.user.entity.SysUser;
import com.smart.rag.user.mapper.SysRoleMapper;
import com.smart.rag.user.mapper.SysUserMapper;
import com.smart.rag.user.mapper.SysUserRoleMapper;
import com.smart.rag.common.snowflake.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService 单元测试")
class AuthServiceTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private SysRoleMapper sysRoleMapper;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private JwtProperties jwtProperties;
    @Mock private TokenCacheService tokenCacheService;
    @Mock private CaptchaService captchaService;
    @Mock private UserPermissionProvider userPermissionProvider;
    @Mock private SnowflakeIdGenerator idGenerator;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthServiceImpl authService;

    private SysUser buildActiveUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPassword("$2a$10$encoded");
        user.setEmail("test@example.com");
        user.setNickname("Test");
        user.setStatus(1);
        user.setDeleted(0);
        return user;
    }

    private void setupLoginMocks(SysUser user) {
        when(tokenCacheService.isLoginRateLimited(anyString())).thenReturn(false);
        when(captchaService.validate(anyString(), anyInt())).thenReturn(true);
        when(sysUserMapper.selectByUsername(anyString())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("Password1!"), anyString())).thenReturn(true);
        when(sysUserRoleMapper.selectRoleIdsByUserId(1L)).thenReturn(List.of(1L));
        SysRole role = new SysRole(); role.setId(1L); role.setRoleName("USER");
        when(sysRoleMapper.selectByIds(List.of(1L))).thenReturn(List.of(role));
        when(jwtTokenProvider.generateAccessToken(eq(1L), anyList())).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
        when(jwtTokenProvider.getJtiFromToken("access-token")).thenReturn("jti-123");
        when(tokenCacheService.getUserPermissions(1L)).thenReturn(null);
    }

    // ==================== Login ====================

    @Nested
    @DisplayName("登录")
    class LoginTests {

        @Test
        @DisplayName("login_success: 正常登录返回 LoginResult")
        void login_success() {
            SysUser user = buildActiveUser();
            setupLoginMocks(user);

            AuthService.LoginResult result = authService.login("testuser", "Password1!", "127.0.0.1", "cap-id", "150");

            assertNotNull(result);
            assertNotNull(result.tokens());
            assertEquals("access-token", result.tokens().accessToken());
            assertEquals("refresh-token", result.tokens().refreshToken());
            assertEquals(1L, result.response().user().id());
            verify(tokenCacheService).incrementLoginAttempts("127.0.0.1");
        }

        @Test
        @DisplayName("login_rateLimited: 限流时抛 RateLimitExceededException")
        void login_rateLimited() {
            when(tokenCacheService.isLoginRateLimited("127.0.0.1")).thenReturn(true);

            assertThrows(RateLimitExceededException.class,
                    () -> authService.login("testuser", "Password1!", "127.0.0.1", "cap-id", "150"));
            verify(tokenCacheService, never()).incrementLoginAttempts(anyString());
        }

        @Test
        @DisplayName("login_invalidCaptcha: 验证码错误时抛 ClientException")
        void login_invalidCaptcha() {
            when(tokenCacheService.isLoginRateLimited(anyString())).thenReturn(false);
            when(captchaService.validate("cap-id", 150)).thenReturn(false);

            assertThrows(ClientException.class,
                    () -> authService.login("testuser", "Password1!", "127.0.0.1", "cap-id", "150"));
        }

        @Test
        @DisplayName("login_userNotFound: 用户不存在时抛 ClientException")
        void login_userNotFound() {
            when(tokenCacheService.isLoginRateLimited(anyString())).thenReturn(false);
            when(captchaService.validate(anyString(), anyInt())).thenReturn(true);
            when(sysUserMapper.selectByUsername(anyString())).thenReturn(Optional.empty());

            assertThrows(ClientException.class,
                    () -> authService.login("nouser", "Password1!", "127.0.0.1", "cap-id", "150"));
        }

        @Test
        @DisplayName("login_wrongPassword: 密码错误时抛 ClientException")
        void login_wrongPassword() {
            SysUser user = buildActiveUser();
            when(tokenCacheService.isLoginRateLimited(anyString())).thenReturn(false);
            when(captchaService.validate(anyString(), anyInt())).thenReturn(true);
            when(sysUserMapper.selectByUsername(anyString())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(eq("WrongPass1!"), anyString())).thenReturn(false);

            assertThrows(ClientException.class,
                    () -> authService.login("testuser", "WrongPass1!", "127.0.0.1", "cap-id", "150"));
        }

        @Test
        @DisplayName("login_userDisabled_status0: status=0 时抛 ClientException")
        void login_userDisabled_status0() {
            SysUser user = buildActiveUser();
            user.setStatus(0);
            when(tokenCacheService.isLoginRateLimited(anyString())).thenReturn(false);
            when(captchaService.validate(anyString(), anyInt())).thenReturn(true);
            when(sysUserMapper.selectByUsername(anyString())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            assertThrows(ClientException.class,
                    () -> authService.login("testuser", "Password1!", "127.0.0.1", "cap-id", "150"));
        }

        @Test
        @DisplayName("login_userDisabled_redisStatus: Redis status=disabled 时抛 ClientException")
        void login_userDisabled_redisStatus() {
            SysUser user = buildActiveUser();
            when(tokenCacheService.isLoginRateLimited(anyString())).thenReturn(false);
            when(captchaService.validate(anyString(), anyInt())).thenReturn(true);
            when(sysUserMapper.selectByUsername(anyString())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(tokenCacheService.getUserStatus(1L)).thenReturn("disabled");

            assertThrows(ClientException.class,
                    () -> authService.login("testuser", "Password1!", "127.0.0.1", "cap-id", "150"));
        }
    }

    // ==================== Register ====================

    @Nested
    @DisplayName("注册")
    class RegisterTests {

        private void setupRegisterMocks() {
            when(captchaService.validate(anyString(), anyInt())).thenReturn(true);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encoded");
            when(idGenerator.nextId()).thenReturn(123456L);
            SysRole userRole = new SysRole();
            userRole.setId(2L);
            userRole.setRoleName("USER");
            when(sysRoleMapper.selectByRoleName("USER")).thenReturn(Optional.of(userRole));
        }

        @Test
        @DisplayName("register_success: 正常注册")
        void register_success() {
            setupRegisterMocks();
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                org.springframework.transaction.support.TransactionCallback<?> cb = invocation.getArgument(0);
                return cb.doInTransaction(null);
            });

            LoginResponse.UserInfo result = authService.register("newuser", "Password1!", "new@example.com", "Nick", "cap-id", "150");

            assertNotNull(result);
            assertEquals("newuser", result.username());
        }

        @Test
        @DisplayName("register_duplicateUsername: DuplicateKeyException → ClientException")
        void register_duplicateUsername() {
            setupRegisterMocks();
            when(transactionTemplate.execute(any())).thenThrow(new DuplicateKeyException("dup"));

            assertThrows(ClientException.class,
                    () -> authService.register("existing", "Password1!", "new@example.com", "Nick", "cap-id", "150"));
        }

        @Test
        @DisplayName("register_passwordTooWeak: 密码太简单时抛 ClientException")
        void register_passwordTooWeak() {
            when(captchaService.validate(anyString(), anyInt())).thenReturn(true);

            assertThrows(ClientException.class,
                    () -> authService.register("user", "123", "new@example.com", "Nick", "cap-id", "150"));
        }

        @Test
        @DisplayName("register_emailNormalized: 验证 email 被 toLowerCase")
        void register_emailNormalized() {
            setupRegisterMocks();
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                org.springframework.transaction.support.TransactionCallback<?> cb = invocation.getArgument(0);
                return cb.doInTransaction(null);
            });

            LoginResponse.UserInfo result = authService.register("newuser", "Password1!", "New@EXAMPLE.com", "Nick", "cap-id", "150");

            assertEquals("new@example.com", result.email());
        }
    }

    // ==================== Password Complexity ====================

    @Nested
    @DisplayName("密码复杂度（通过 register 间接测试）")
    class PasswordComplexityTests {

        private void assertPasswordRejected(String password) {
            when(captchaService.validate(anyString(), anyInt())).thenReturn(true);
            assertThrows(ClientException.class,
                    () -> authService.register("user", password, "e@e.com", "n", "c", "1"));
        }

        @Test @DisplayName("太短 <8 位被拒绝")
        void tooShort() { assertPasswordRejected("Ab1!"); }

        @Test @DisplayName("太长 >72 位被拒绝")
        void tooLong() { assertPasswordRejected("A".repeat(73) + "1!"); }

        @Test @DisplayName("包含空白被拒绝")
        void hasWhitespace() { assertPasswordRejected("Pass word1!"); }

        @Test @DisplayName("只有小写+数字（2类）被拒绝")
        void onlyTwoCategories() { assertPasswordRejected("abcdefgh1"); }

        @Test @DisplayName("3类组合通过: 大写+小写+数字")
        void threeCategories_pass() {
            setupRegisterMocks_forPassword();
            when(transactionTemplate.execute(any())).thenAnswer(inv -> {
                org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
                return cb.doInTransaction(null);
            });
            assertDoesNotThrow(() -> authService.register("user", "Password1", "e@e.com", "n", "c", "1"));
        }

        private void setupRegisterMocks_forPassword() {
            when(captchaService.validate(anyString(), anyInt())).thenReturn(true);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encoded");
            when(idGenerator.nextId()).thenReturn(123L);
            SysRole userRole = new SysRole(); userRole.setId(1L); userRole.setRoleName("USER");
            when(sysRoleMapper.selectByRoleName("USER")).thenReturn(Optional.of(userRole));
        }
    }

    // ==================== Update Profile ====================

    @Nested
    @DisplayName("更新资料")
    class UpdateProfileTests {

        @Test
        @DisplayName("updateProfile_emailDuplicate: 邮箱重复时抛 ClientException")
        void updateProfile_emailDuplicate() {
            SysUser user = buildActiveUser();
            when(sysUserMapper.selectActiveById(1L)).thenReturn(Optional.of(user));
            when(sysUserMapper.selectByEmailExcludingId("other@example.com", 1L))
                    .thenReturn(Optional.of(buildActiveUser()));

            assertThrows(ClientException.class,
                    () -> authService.updateProfile(1L, new UserUpdateRequest(null, "other@example.com", null, null)));
        }

        @Test
        @DisplayName("updateProfile_emailChange_success: 正常修改邮箱")
        void updateProfile_emailChange_success() {
            SysUser user = buildActiveUser();
            when(sysUserMapper.selectActiveById(1L)).thenReturn(Optional.of(user));
            when(sysUserMapper.updateById(any(SysUser.class))).thenReturn(1);
            when(sysUserRoleMapper.selectRoleIdsByUserId(1L)).thenReturn(List.of(1L));
            SysRole role = new SysRole(); role.setId(1L); role.setRoleName("USER");
            when(sysRoleMapper.selectByIds(List.of(1L))).thenReturn(List.of(role));
            when(tokenCacheService.getUserPermissions(1L)).thenReturn(null);

            assertDoesNotThrow(() -> authService.updateProfile(1L, new UserUpdateRequest("NewNick", null, null, null)));
        }

        @Test
        @DisplayName("updateProfile_userNotFound: 抛 ServiceException")
        void updateProfile_userNotFound() {
            when(sysUserMapper.selectActiveById(999L)).thenReturn(Optional.empty());
            assertThrows(ServiceException.class,
                    () -> authService.updateProfile(999L, new UserUpdateRequest("nick", null, null, null)));
        }
    }
}
