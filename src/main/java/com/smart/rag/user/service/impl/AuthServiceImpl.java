package com.smart.rag.user.service.impl;

import com.smart.rag.common.snowflake.SnowflakeIdGenerator;
import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.exception.RateLimitExceededException;
import com.smart.rag.security.config.JwtProperties;
import com.smart.rag.security.service.CaptchaService;
import com.smart.rag.security.service.TokenCacheService;
import com.smart.rag.security.util.JwtTokenProvider;
import com.smart.rag.user.dto.LoginResponse;
import com.smart.rag.user.dto.UserUpdateRequest;
import com.smart.rag.user.entity.SysRole;
import com.smart.rag.user.entity.SysUser;
import com.smart.rag.user.entity.SysUserRole;
import com.smart.rag.user.mapper.SysRoleMapper;
import com.smart.rag.user.mapper.SysRolePermissionMapper;
import com.smart.rag.user.mapper.SysUserMapper;
import com.smart.rag.user.mapper.SysUserRoleMapper;
import com.smart.rag.user.service.AuthService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysRoleMapper sysRoleMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final TokenCacheService tokenCacheService;
    private final CaptchaService captchaService;
    private final SnowflakeIdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                       SysUserRoleMapper sysUserRoleMapper,
                       SysRolePermissionMapper sysRolePermissionMapper,
                       SysRoleMapper sysRoleMapper,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       TokenCacheService tokenCacheService,
                       CaptchaService captchaService,
                       SnowflakeIdGenerator idGenerator,
                       TransactionTemplate transactionTemplate,
                       PasswordEncoder passwordEncoder) {
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRolePermissionMapper = sysRolePermissionMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.tokenCacheService = tokenCacheService;
        this.captchaService = captchaService;
        this.idGenerator = idGenerator;
        this.transactionTemplate = transactionTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public LoginResult login(String username, String password, String ip,
                                String captchaId, String captchaCode) {
        // 1. IP rate limit check
        if (tokenCacheService.isLoginRateLimited(ip)) {
            throw new RateLimitExceededException("登录尝试过于频繁，请5分钟后再试");
        }
        tokenCacheService.incrementLoginAttempts(ip);

        // 2. Captcha validation
        validateCaptcha(captchaId, captchaCode);

        // 3. Query user
        SysUser user = sysUserMapper.selectByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        // 4. Verify password
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        // 5. Check user status
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        // 6. Check Redis status
        String redisStatus = tokenCacheService.getUserStatus(user.getId());
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        // 7. Query roles & generate tokens
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        List<String> roleNames = getRoleNames(roleIds);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), roleNames);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        String tokenId = jwtTokenProvider.getJtiFromToken(accessToken);
        tokenCacheService.storeAccessToken(user.getId(), tokenId, roleNames);
        tokenCacheService.storeRefreshToken(refreshToken, user.getId());

        loadUserPermissions(user.getId());

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
                                            String nickname, String captchaId, String captchaCode) {
        validateCaptcha(captchaId, captchaCode);

        String normalizedUsername = username.trim();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (!isPasswordComplexEnough(password)) {
            throw new BusinessException(ErrorCode.PASSWORD_RULE_ERROR);
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
                user.setNickname(nickname != null ? nickname.trim() : normalizedUsername);
                user.setStatus(1);
                sysUserMapper.insert(user);

                SysRole userRole = sysRoleMapper.selectByRoleName("USER")
                        .orElseThrow(() -> new RuntimeException("默认 USER 角色未找到，请检查数据库初始化"));
                SysUserRole userRoleBinding = new SysUserRole();
                userRoleBinding.setUserId(user.getId());
                userRoleBinding.setRoleId(userRole.getId());
                sysUserRoleMapper.insert(userRoleBinding);

                return user;
            });
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名或邮箱已存在");
        }

        if (newUser == null) {
            throw new RuntimeException("注册失败");
        }

        return new LoginResponse.UserInfo(
            newUser.getId(), newUser.getUsername(), newUser.getNickname(),
            newUser.getEmail(), newUser.getAvatar(), List.of("USER")
        );
    }

    @Override
    public LoginResult refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_REFRESH_INVALID);
        }

        if (!"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            throw new BusinessException(ErrorCode.TOKEN_NOT_REFRESH);
        }

        Long userId = tokenCacheService.rotateRefreshToken(refreshToken);
        if (userId == null) {
            throw new BusinessException(ErrorCode.TOKEN_REFRESH_EXPIRED);
        }

        SysUser user = sysUserMapper.selectActiveById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_STATUS_ABNORMAL));
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.USER_STATUS_ABNORMAL);
        }

        String redisStatus = tokenCacheService.getUserStatus(userId);
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        List<String> roleNames = getRoleNames(roleIds);

        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, roleNames);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(userId);

        String tokenId = jwtTokenProvider.getJtiFromToken(newAccessToken);
        tokenCacheService.storeAccessToken(userId, tokenId, roleNames);
        tokenCacheService.storeRefreshToken(newRefreshToken, userId);

        TokenPair tokenPair = new TokenPair(newAccessToken, newRefreshToken);
        LoginResponse response = new LoginResponse(
            new LoginResponse.UserInfo(
                user.getId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getAvatar(), roleNames
            )
        );
        return new LoginResult(tokenPair, response);
    }

    @Override
    public void logout(Long userId, String accessToken) {
        tokenCacheService.revokeAllTokens(userId);
        tokenCacheService.evictUserPermissions(userId);
    }

    @Override
    public LoginResponse.UserInfo getCurrentUser(Long userId) {
        SysUser user = sysUserMapper.selectActiveById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        List<String> roleNames = getRoleNames(roleIds);

        Set<String> permissions = tokenCacheService.getUserPermissions(userId);
        if (permissions == null) {
            loadUserPermissions(userId);
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
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_ERROR);
        }

        if (!isPasswordComplexEnough(newPassword)) {
            throw new BusinessException(ErrorCode.PASSWORD_RULE_ERROR);
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);

        tokenCacheService.revokeAllTokens(userId);
        tokenCacheService.evictUserPermissions(userId);
    }

    @Override
    public void revokeAllUserTokens(Long userId) {
        tokenCacheService.revokeAllTokens(userId);
        tokenCacheService.evictUserPermissions(userId);
        tokenCacheService.markUserStatus(userId, "disabled");
    }

    @Override
    public Set<String> loadUserPermissions(Long userId) {
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            tokenCacheService.cacheUserPermissions(userId, Set.of());
            return Set.of();
        }

        Set<String> permissions = new HashSet<>();
        var perms = sysRolePermissionMapper.selectPermissionsByRoleIds(roleIds);
        for (var p : perms) {
            if (p.getPermissionName() != null) {
                permissions.add(p.getPermissionName());
            }
        }

        tokenCacheService.cacheUserPermissions(userId, permissions);
        return permissions;
    }

    @Override
    public LoginResponse.UserInfo updateProfile(Long userId, UserUpdateRequest request) {
        SysUser user = sysUserMapper.selectActiveById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (request.nickname() != null) user.setNickname(request.nickname().trim());
        if (request.email() != null) {
            String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
            if (!normalizedEmail.equals(user.getEmail())) {
                sysUserMapper.selectByEmailExcludingId(normalizedEmail, userId)
                        .ifPresent(existing -> { throw new BusinessException(ErrorCode.EMAIL_USED); });
            }
            user.setEmail(normalizedEmail);
        }
        if (request.phone() != null) user.setPhone(request.phone().isBlank() ? null : request.phone().trim());
        if (request.avatar() != null) user.setAvatar(request.avatar());
        sysUserMapper.updateById(user);

        return getCurrentUser(userId);
    }

    // ==================== Private helpers ====================

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
            throw new BusinessException(ErrorCode.CAPTCHA_PARAM_MISSING);
        }
        int submittedX;
        try {
            submittedX = Integer.parseInt(captchaCode);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.CAPTCHA_FORMAT_ERROR);
        }
        if (!captchaService.validate(captchaId, submittedX)) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
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
