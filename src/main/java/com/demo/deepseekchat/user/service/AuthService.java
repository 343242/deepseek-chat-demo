package com.demo.deepseekchat.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.deepseekchat.common.snowflake.SnowflakeIdGenerator;
import com.demo.deepseekchat.exception.BusinessException;
import com.demo.deepseekchat.exception.RateLimitExceededException;
import com.demo.deepseekchat.security.config.JwtProperties;
import com.demo.deepseekchat.security.service.CaptchaService;
import com.demo.deepseekchat.security.service.TokenCacheService;
import com.demo.deepseekchat.security.util.JwtTokenProvider;
import com.demo.deepseekchat.user.dto.*;
import com.demo.deepseekchat.user.entity.SysRole;
import com.demo.deepseekchat.user.entity.SysUser;
import com.demo.deepseekchat.user.entity.SysUserRole;
import com.demo.deepseekchat.user.mapper.SysRoleMapper;
import com.demo.deepseekchat.user.mapper.SysRolePermissionMapper;
import com.demo.deepseekchat.user.mapper.SysUserMapper;
import com.demo.deepseekchat.user.mapper.SysUserRoleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import java.util.Locale;

@Service
public class AuthService {

    /**
     * 密码复杂度：至少 8 位不超过 72 位，必须包含大写、小写、数字、显式特殊字符中至少 3 种。
     * 不允许空白字符。
     */
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

    public AuthService(SysUserMapper sysUserMapper,
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

    public record TokenPair(String accessToken, String refreshToken) {}

    /**
     * Login: validates credentials and returns token pair + user info.
     * Caller (controller) is responsible for setting cookies.
     */
    public record LoginResult(TokenPair tokens, LoginResponse response) {}

    public LoginResult login(String username, String password, String ip,
                                String captchaId, String captchaCode) {
        // 1. IP rate limit check（在验证码之前，防止暴力破解验证码不计入限流）
        if (tokenCacheService.isLoginRateLimited(ip)) {
            throw new RateLimitExceededException("登录尝试过于频繁，请5分钟后再试");
        }
        tokenCacheService.incrementLoginAttempts(ip);

        // 2. Captcha validation
        validateCaptcha(captchaId, captchaCode);

        // 2. Query user
        SysUser user = sysUserMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleted, 0)
        );
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        // 3. Verify password（先验密码，防止枚举账号状态）
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 4. Check user status
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("用户名或密码错误");
        }

        // 5. Check Redis status
        String redisStatus = tokenCacheService.getUserStatus(user.getId());
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new BusinessException("用户名或密码错误");
        }

        // 6. Query roles
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        List<String> roleNames = getRoleNames(roleIds);

        // 7. Generate tokens
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), roleNames);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // 8. Store in Redis (P2-11: use jti as tokenId)
        String tokenId = jwtTokenProvider.getJtiFromToken(accessToken);
        tokenCacheService.storeAccessToken(user.getId(), tokenId, roleNames);
        tokenCacheService.storeRefreshToken(refreshToken, user.getId());

        // 9. Cache permissions
        Set<String> permissions = loadUserPermissions(user.getId());

        // 10. Return result
        TokenPair tokenPair = new TokenPair(accessToken, refreshToken);
        LoginResponse response = new LoginResponse(
            new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getAvatar(),
                roleNames
            )
        );
        return new LoginResult(tokenPair, response);
    }

    public LoginResponse.UserInfo register(String username, String password, String email,
                                            String nickname, String captchaId, String captchaCode) {
        // 0. Captcha validation
        validateCaptcha(captchaId, captchaCode);

        // 1. Normalize inputs
        String normalizedUsername = username.trim();
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        // 2. Password strength (at least 3 of: upper, lower, digit, special)
        if (!isPasswordComplexEnough(password)) {
            throw new BusinessException(PASSWORD_RULE_MSG);
        }

        // 3. Encode password
        String encodedPassword = passwordEncoder.encode(password);

        // 4. Programmatic transaction（数据库唯一索引兜底 + DuplicateKeyException 兜底）
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

                // Assign default USER role
                SysRole userRole = sysRoleMapper.selectOne(
                        new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleName, "USER"));
                if (userRole == null) {
                    throw new RuntimeException("默认 USER 角色未找到，请检查数据库初始化");
                }
                SysUserRole userRoleBinding = new SysUserRole();
                userRoleBinding.setUserId(user.getId());
                userRoleBinding.setRoleId(userRole.getId());
                sysUserRoleMapper.insert(userRoleBinding);

                return user;
            });
        } catch (DuplicateKeyException e) {
            throw new BusinessException("用户名或邮箱已存在");
        }

        if (newUser == null) {
            throw new RuntimeException("注册失败");
        }

        return new LoginResponse.UserInfo(
            newUser.getId(),
            newUser.getUsername(),
            newUser.getNickname(),
            newUser.getEmail(),
            newUser.getAvatar(),
            List.of("USER")
        );
    }

    public LoginResult refreshToken(String refreshToken) {
        // 1. Validate signature
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException("无效的刷新令牌");
        }

        // 2. Check token type
        if (!"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            throw new BusinessException("不是刷新令牌");
        }

        // 3. Atomically rotate (revoke + get userId) via Lua script
        Long userId = tokenCacheService.rotateRefreshToken(refreshToken);
        if (userId == null) {
            throw new BusinessException("刷新令牌已过期或已吊销");
        }

        // 4. Check user status
        SysUser user = sysUserMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, 0)
        );
        if (user == null || (user.getStatus() != null && user.getStatus() != 1)) {
            throw new BusinessException("用户状态异常");
        }

        String redisStatus = tokenCacheService.getUserStatus(userId);
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new BusinessException("账号已被禁用");
        }

        // 5. Generate new tokens
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        List<String> roleNames = getRoleNames(roleIds);

        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, roleNames);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(userId);

        // 6. Store new tokens
        String tokenId = jwtTokenProvider.getJtiFromToken(newAccessToken);
        tokenCacheService.storeAccessToken(userId, tokenId, roleNames);
        tokenCacheService.storeRefreshToken(newRefreshToken, userId);

        // 7. Return new token pair
        TokenPair tokenPair = new TokenPair(newAccessToken, newRefreshToken);
        LoginResponse response = new LoginResponse(
            new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getAvatar(),
                roleNames
            )
        );
        return new LoginResult(tokenPair, response);
    }

    public void logout(Long userId, String accessToken) {
        tokenCacheService.revokeAllTokens(userId);
        tokenCacheService.evictUserPermissions(userId);
    }

    public LoginResponse.UserInfo getCurrentUser(Long userId) {
        SysUser user = sysUserMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, 0)
        );
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        List<String> roleNames = getRoleNames(roleIds);

        // Ensure permissions are cached (for internal authorization use)
        Set<String> permissions = tokenCacheService.getUserPermissions(userId);
        if (permissions == null) {
            loadUserPermissions(userId);
        }

        return new LoginResponse.UserInfo(
            user.getId(),
            user.getUsername(),
            user.getNickname(),
            user.getEmail(),
            user.getAvatar(),
            roleNames
        );
    }

    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("旧密码错误");
        }

        if (!isPasswordComplexEnough(newPassword)) {
            throw new BusinessException(PASSWORD_RULE_MSG);
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);

        tokenCacheService.revokeAllTokens(userId);
        tokenCacheService.evictUserPermissions(userId);
    }

    public void revokeAllUserTokens(Long userId) {
        tokenCacheService.revokeAllTokens(userId);
        tokenCacheService.evictUserPermissions(userId);
        tokenCacheService.markUserStatus(userId, "disabled");
    }

    /**
     * P0-1 修复：使用 permissionName 而非 resourceKey 作为 GrantedAuthority
     */
    public Set<String> loadUserPermissions(Long userId) {
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            tokenCacheService.cacheUserPermissions(userId, Set.of());
            return Set.of();
        }

        Set<String> permissions = new HashSet<>();

        // Batch query: eliminate N+1
        List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);

        var perms = sysRolePermissionMapper.selectPermissionsByRoleIds(roleIds);
        for (var p : perms) {
            if (p.getPermissionName() != null) {
                permissions.add(p.getPermissionName());
            }
        }

        tokenCacheService.cacheUserPermissions(userId, permissions);
        return permissions;
    }

    /**
     * P2-14: batch query role names instead of N+1 selectById
     */
    private List<String> getRoleNames(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
        return roles.stream()
                .filter(Objects::nonNull)
                .map(SysRole::getRoleName)
                .collect(Collectors.toList());
    }

    // ==================== Captcha ====================

    private void validateCaptcha(String captchaId, String captchaCode) {
        if (captchaId == null || captchaCode == null) {
            throw new BusinessException("验证码参数缺失");
        }
        int submittedX;
        try {
            submittedX = Integer.parseInt(captchaCode);
        } catch (NumberFormatException e) {
            throw new BusinessException("验证码格式错误");
        }
        if (!captchaService.validate(captchaId, submittedX)) {
            throw new BusinessException("验证码错误或已过期");
        }
    }

    /**
     * 密码复杂度校验：至少包含大写、小写、数字、显式特殊字符中的 3 种。
     * 不允许空白字符，长度 8-72。
     */
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

    public LoginResponse.UserInfo updateProfile(Long userId, UserUpdateRequest request) {
        SysUser user = sysUserMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, 0)
        );
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        if (request.nickname() != null) user.setNickname(request.nickname());
        if (request.email() != null) user.setEmail(request.email());
        if (request.phone() != null) user.setPhone(request.phone());
        if (request.avatar() != null) user.setAvatar(request.avatar());
        sysUserMapper.updateById(user);

        return getCurrentUser(userId);
    }
}
