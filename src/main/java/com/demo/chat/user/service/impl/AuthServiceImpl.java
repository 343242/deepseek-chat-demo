package com.demo.chat.user.service.impl;

import com.demo.chat.common.snowflake.SnowflakeIdGenerator;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.exception.RateLimitExceededException;
import com.demo.chat.security.config.JwtProperties;
import com.demo.chat.security.service.CaptchaService;
import com.demo.chat.security.service.TokenCacheService;
import com.demo.chat.security.util.JwtTokenProvider;
import com.demo.chat.user.dto.*;
import com.demo.chat.user.entity.SysRole;
import com.demo.chat.user.entity.SysUser;
import com.demo.chat.user.entity.SysUserRole;
import com.demo.chat.user.mapper.SysRoleMapper;
import com.demo.chat.user.mapper.SysRolePermissionMapper;
import com.demo.chat.user.mapper.SysUserMapper;
import com.demo.chat.user.mapper.SysUserRoleMapper;
import com.demo.chat.user.service.AuthService;
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
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));

        // 4. Verify password
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 5. Check user status
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("用户名或密码错误");
        }

        // 6. Check Redis status
        String redisStatus = tokenCacheService.getUserStatus(user.getId());
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new BusinessException("用户名或密码错误");
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
            throw new BusinessException(PASSWORD_RULE_MSG);
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
            throw new BusinessException("用户名或邮箱已存在");
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
            throw new BusinessException("无效的刷新令牌");
        }

        if (!"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            throw new BusinessException("不是刷新令牌");
        }

        Long userId = tokenCacheService.rotateRefreshToken(refreshToken);
        if (userId == null) {
            throw new BusinessException("刷新令牌已过期或已吊销");
        }

        SysUser user = sysUserMapper.selectActiveById(userId)
                .orElseThrow(() -> new BusinessException("用户状态异常"));
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("用户状态异常");
        }

        String redisStatus = tokenCacheService.getUserStatus(userId);
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new BusinessException("账号已被禁用");
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
                .orElseThrow(() -> new BusinessException("用户不存在"));

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
                .orElseThrow(() -> new BusinessException("用户不存在"));

        if (request.nickname() != null) user.setNickname(request.nickname().trim());
        if (request.email() != null) {
            String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
            if (!normalizedEmail.equals(user.getEmail())) {
                sysUserMapper.selectByEmailExcludingId(normalizedEmail, userId)
                        .ifPresent(existing -> { throw new BusinessException("邮箱已被使用"); });
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
