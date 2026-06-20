package com.smart.rag.user.service.impl;

import com.smart.rag.common.snowflake.SnowflakeIdGenerator;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.exception.RateLimitExceededException;
import com.smart.rag.infrastructure.web.config.JwtProperties;
import com.smart.rag.infrastructure.web.service.CaptchaService;
import com.smart.rag.infrastructure.web.service.TokenCacheService;
import com.smart.rag.infrastructure.web.util.JwtTokenProvider;
import com.smart.rag.infrastructure.web.auth.UserPermissionProvider;
import com.smart.rag.user.dto.LoginResponse;
import com.smart.rag.user.dto.UserUpdateRequest;
import com.smart.rag.user.entity.SysRole;
import com.smart.rag.user.entity.SysUser;
import com.smart.rag.user.entity.SysUserRole;
import com.smart.rag.user.mapper.SysRoleMapper;
import com.smart.rag.user.mapper.SysUserMapper;
import com.smart.rag.user.mapper.SysUserRoleMapper;
import com.smart.rag.user.service.AuthService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.Executor;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import java.util.Locale;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Pattern SPECIAL_CHAR_PATTERN = Pattern.compile(
            "[!@#$%^&*()_+\\-=\\[\\]{};:'\",.<>/?|`~]"
    );

    private static final String PASSWORD_RULE_MSG = "密码需8-72位，不允许空白字符，需包含大写字母、小写字母、数字、特殊字符中至少3种";

    /** 注册时分配的默认角色名 */
    private static final String DEFAULT_ROLE_NAME = "USER";

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final TokenCacheService tokenCacheService;
    private final CaptchaService captchaService;
    private final UserPermissionProvider userPermissionProvider;
    private final SnowflakeIdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder;
    private final Executor permissionWarmupExecutor;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                       SysUserRoleMapper sysUserRoleMapper,
                       SysRoleMapper sysRoleMapper,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       TokenCacheService tokenCacheService,
                       CaptchaService captchaService,
                       UserPermissionProvider userPermissionProvider,
                       SnowflakeIdGenerator idGenerator,
                       TransactionTemplate transactionTemplate,
                       PasswordEncoder passwordEncoder,
                       @Qualifier("authPermissionWarmupExecutor") Executor permissionWarmupExecutor) {
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.tokenCacheService = tokenCacheService;
        this.captchaService = captchaService;
        this.userPermissionProvider = userPermissionProvider;
        this.idGenerator = idGenerator;
        this.transactionTemplate = transactionTemplate;
        this.passwordEncoder = passwordEncoder;
        this.permissionWarmupExecutor = permissionWarmupExecutor;
    }

    @Override
    public LoginResult login(String username, String password, String ip,
                                String captchaId, String captchaCode) {
        // 1. Captcha first — 错误验证码不应消耗 IP 登录次数（避免被用来恶意锁死账户）
        validateCaptcha(captchaId, captchaCode);

        // 2. IP rate limit — 合并检查+递增为单次 Redis 往返
        long attemptCount = tokenCacheService.checkAndIncrementLoginAttempts(ip);
        if (attemptCount < 0) {
            log.warn("Login rate-limited: ip={}, username={}", ip, username);
            throw new RateLimitExceededException("登录尝试过于频繁，请5分钟后再试");
        }

        // 3. Query user
        SysUser user = sysUserMapper.selectByUsername(username)
                .orElseThrow(() -> {
                    log.warn("Login failed (user not found): ip={}, username={}", ip, username);
                    return new ClientException(ClientErrorCode.LOGIN_FAILED);
                });

        // 4. Verify password
        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("Login failed (bad password): ip={}, username={}", ip, username);
            throw new ClientException(ClientErrorCode.LOGIN_FAILED);
        }

        // 5. Check DB user status
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new ClientException(ClientErrorCode.LOGIN_FAILED);
        }

        // 6. Query roles & permissions（复用 roleIds，避免 loadUserPermissions 重复查询）
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        List<String> roleNames = getRoleNames(roleIds);
        // 权限预热：异步执行，不阻塞登录响应；best-effort，失败仅记日志（miss 由 getCurrentUser 兜底）
        warmupUserPermissions(user.getId());

        // 7. Generate tokens
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), roleNames);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        String tokenId = jwtTokenProvider.getJtiFromToken(accessToken);

        // 8. Pipeline 批量：用户状态检查 + Token 存储（3+ Redis → 1 Pipeline 往返）
        String redisStatus = tokenCacheService.batchStoreTokens(
                user.getId(), tokenId, roleNames, refreshToken,
                jwtProperties.accessExpiration(), jwtProperties.refreshExpiration());
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new ClientException(ClientErrorCode.LOGIN_FAILED);
        }

        TokenPair tokenPair = new TokenPair(accessToken, refreshToken);
        LoginResponse response = new LoginResponse(
            new LoginResponse.UserInfo(
                user.getId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getAvatar(), roleNames
            )
        );
        return new LoginResult(tokenPair, response);
    }


    @Override
    public LoginResponse.UserInfo register(String username, String password, String email,
                                            String nickname, String captchaId, String captchaCode, String ip) {
        validateCaptcha(captchaId, captchaCode);

        if (ip != null) {
            long attemptCount = tokenCacheService.checkAndIncrementLoginAttempts(ip);
            if (attemptCount < 0) {
                log.warn("Register rate-limited: ip={}, username={}", ip, username);
                throw new RateLimitExceededException("注册尝试过于频繁，请5分钟后再试");
            }
        }

        String normalizedUsername = username.trim();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (!isPasswordComplexEnough(password)) {
            throw new ClientException(ClientErrorCode.PASSWORD_RULE_ERROR);
        }

        String encodedPassword = passwordEncoder.encode(password);

        SysUser newUser;
        try {
            newUser = transactionTemplate.execute(status -> {
                SysUser user = new SysUser();
                user.setId(idGenerator.nextId());
                user.setUsername(normalizedUsername);
                user.setPassword(encodedPassword);
                user.setEmail(normalizedEmail);
                user.setNickname((nickname == null || nickname.isBlank()) ? normalizedUsername : nickname.trim());
                user.setStatus(1);
                sysUserMapper.insert(user);

                SysRole userRole = sysRoleMapper.selectByRoleName(DEFAULT_ROLE_NAME)
                        .orElseThrow(() -> new ServiceException(ServiceErrorCode.ROLE_NOT_FOUND,
                                "默认 " + DEFAULT_ROLE_NAME + " 角色未找到，请检查数据库初始化"));
                SysUserRole userRoleBinding = new SysUserRole();
                userRoleBinding.setUserId(user.getId());
                userRoleBinding.setRoleId(userRole.getId());
                sysUserRoleMapper.insert(userRoleBinding);

                return user;
            });
        } catch (DuplicateKeyException e) {
            throw new ClientException(ClientErrorCode.USERNAME_EXISTS, "用户名或邮箱已存在");
        }

        if (newUser == null) {
            throw new ServiceException(ServiceErrorCode.INTERNAL_ERROR, "注册失败");
        }

        return new LoginResponse.UserInfo(
            newUser.getId(), newUser.getUsername(), newUser.getNickname(),
            newUser.getEmail(), newUser.getAvatar(), List.of(DEFAULT_ROLE_NAME)
        );
    }

    @Override
    public LoginResult refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new ClientException(ClientErrorCode.TOKEN_REFRESH_INVALID);
        }

        if (!"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            throw new ClientException(ClientErrorCode.TOKEN_NOT_REFRESH);
        }

        Long userId = tokenCacheService.rotateRefreshToken(refreshToken);
        if (userId == null) {
            throw new ClientException(ClientErrorCode.TOKEN_REFRESH_EXPIRED);
        }

        SysUser user = sysUserMapper.selectActiveById(userId)
                .orElseThrow(() -> new ClientException(ClientErrorCode.USER_STATUS_ABNORMAL));
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new ClientException(ClientErrorCode.USER_STATUS_ABNORMAL);
        }

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        List<String> roleNames = getRoleNames(roleIds);

        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, roleNames);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(userId);

        // Pipeline 批量：用户状态检查 + Token 存储（与 login 一致，1 次往返）
        String tokenId = jwtTokenProvider.getJtiFromToken(newAccessToken);
        String redisStatus = tokenCacheService.batchStoreTokens(
                userId, tokenId, roleNames, newRefreshToken,
                jwtProperties.accessExpiration(), jwtProperties.refreshExpiration());
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new ClientException(ClientErrorCode.USER_DISABLED);
        }

        TokenPair tokenPair = new TokenPair(newAccessToken, newRefreshToken);
        LoginResponse response = new LoginResponse(
            new LoginResponse.UserInfo(
                user.getId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getAvatar(), roleNames
            )
        );
        return new LoginResult(tokenPair, response);
    }

    /**
     * 全端下线：撤销该用户全部会话的 access + refresh token，并清空权限缓存。
     */
    @Override
    public void logout(Long userId) {
        tokenCacheService.revokeAllTokens(userId);
        tokenCacheService.evictUserPermissions(userId);
    }

    @Override
    public LoginResponse.UserInfo getCurrentUser(Long userId) {
        SysUser user = sysUserMapper.selectActiveById(userId)
                .orElseThrow(() -> new ServiceException(ServiceErrorCode.USER_NOT_FOUND));

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        List<String> roleNames = getRoleNames(roleIds);

        Set<String> permissions = tokenCacheService.getUserPermissions(userId);
        if (permissions == null) {
            userPermissionProvider.loadUserPermissions(userId);
        }

        return new LoginResponse.UserInfo(
            user.getId(), user.getUsername(), user.getNickname(),
            user.getEmail(), user.getAvatar(), roleNames
        );
    }

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ServiceErrorCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new ClientException(ClientErrorCode.OLD_PASSWORD_ERROR);
        }

        if (!isPasswordComplexEnough(newPassword)) {
            throw new ClientException(ClientErrorCode.PASSWORD_RULE_ERROR);
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);

        // DB 已提交；Redis 撤销/驱逐失败仅记 WARN（旧 token 最迟在 access 过期或下次状态校验时失效）
        try {
            tokenCacheService.revokeAllTokens(userId);
            tokenCacheService.evictUserPermissions(userId);
        } catch (Exception e) {
            log.warn("Post-changePassword Redis cleanup failed (userId={}): old tokens may remain valid until expiry", userId, e);
        }
    }

    @Override
    public void revokeAllUserTokens(Long userId) {
        tokenCacheService.revokeAllTokens(userId);
        tokenCacheService.evictUserPermissions(userId);
        tokenCacheService.markUserStatus(userId, "disabled");
    }

    @Override
    public LoginResponse.UserInfo updateProfile(Long userId, UserUpdateRequest request) {
        SysUser user = sysUserMapper.selectActiveById(userId)
                .orElseThrow(() -> new ServiceException(ServiceErrorCode.USER_NOT_FOUND));

        if (request.nickname() != null) user.setNickname(request.nickname().trim());
        if (request.email() != null) {
            String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
            if (!normalizedEmail.equals(user.getEmail())) {
                sysUserMapper.selectByEmailExcludingId(normalizedEmail, userId)
                        .ifPresent(existing -> { throw new ClientException(ClientErrorCode.EMAIL_USED); });
            }
            user.setEmail(normalizedEmail);
        }
        if (request.phone() != null) user.setPhone(request.phone().isBlank() ? null : request.phone().trim());
        if (request.avatar() != null) user.setAvatar(request.avatar());
        sysUserMapper.updateById(user);

        return getCurrentUser(userId);
    }

    // ==================== Private helpers ====================

    /** 异步预热用户权限缓存（best-effort，不阻塞调用方；失败仅记日志，miss 由 getCurrentUser 兜底）。 */
    private void warmupUserPermissions(Long userId) {
        try {
            permissionWarmupExecutor.execute(() -> {
                try {
                    userPermissionProvider.loadUserPermissions(userId);
                } catch (Exception e) {
                    log.warn("Permission warmup failed for userId={}", userId, e);
                }
            });
        } catch (Exception e) {
            log.debug("Permission warmup not scheduled for userId={}: {}", userId, e.getMessage());
        }
    }

    private List<String> getRoleNames(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<SysRole> roles = sysRoleMapper.selectByIds(roleIds);
        return roles.stream()
                .filter(Objects::nonNull)
                .map(SysRole::getRoleName)
                .collect(Collectors.toList());
    }

    private void validateCaptcha(String captchaId, String captchaCode) {
        if (captchaId == null || captchaCode == null) {
            throw new ClientException(ClientErrorCode.CAPTCHA_PARAM_MISSING);
        }
        int submittedX;
        try {
            submittedX = Integer.parseInt(captchaCode);
        } catch (NumberFormatException e) {
            throw new ClientException(ClientErrorCode.CAPTCHA_FORMAT_ERROR);
        }
        if (!captchaService.validate(captchaId, submittedX)) {
            throw new ClientException(ClientErrorCode.CAPTCHA_INVALID);
        }
    }

    private boolean isPasswordComplexEnough(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) return false;
        if (password.chars().anyMatch(Character::isWhitespace)) return false;

        int categories = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) categories++;
        if (password.chars().anyMatch(Character::isUpperCase)) categories++;
        if (password.chars().anyMatch(Character::isDigit)) categories++;
        if (SPECIAL_CHAR_PATTERN.matcher(password).find()) categories++;

        return categories >= 3;
    }
}
