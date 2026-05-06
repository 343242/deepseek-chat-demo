package com.demo.deepseekchat.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.deepseekchat.security.config.JwtProperties;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysRoleMapper sysRoleMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final TokenCacheService tokenCacheService;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder;

    public AuthService(SysUserMapper sysUserMapper,
                       SysUserRoleMapper sysUserRoleMapper,
                       SysRolePermissionMapper sysRolePermissionMapper,
                       SysRoleMapper sysRoleMapper,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       TokenCacheService tokenCacheService,
                       TransactionTemplate transactionTemplate,
                       PasswordEncoder passwordEncoder) {
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRolePermissionMapper = sysRolePermissionMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.tokenCacheService = tokenCacheService;
        this.transactionTemplate = transactionTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResponse login(String username, String password, String ip) {
        // 1. IP rate limit check
        if (tokenCacheService.isLoginRateLimited(ip)) {
            throw new IllegalArgumentException("登录尝试过于频繁，请5分钟后再试");
        }
        tokenCacheService.incrementLoginAttempts(ip);

        // 2. Query user
        SysUser user = sysUserMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleted, 0)
        );
        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 3. Check user status
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new IllegalArgumentException("账号已被禁用");
        }

        // 4. Check Redis status
        String redisStatus = tokenCacheService.getUserStatus(user.getId());
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new IllegalArgumentException("账号已被禁用");
        }

        // 5. Verify password
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 6. Query roles
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        List<String> roleNames = getRoleNames(roleIds);

        // 7. Generate tokens
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), roleNames);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // 8. Store in Redis
        String tokenId = extractTokenId(accessToken);
        tokenCacheService.storeAccessToken(user.getId(), tokenId, roleNames);
        tokenCacheService.storeRefreshToken(refreshToken, user.getId());

        // 9. Cache permissions
        Set<String> permissions = loadUserPermissions(user.getId());

        // 10. Return response
        return new LoginResponse(
            accessToken,
            refreshToken,
            "Bearer",
            jwtProperties.accessExpiration(),
            jwtProperties.refreshExpiration(),
            new LoginResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getAvatar(),
                roleNames,
                permissions
            )
        );
    }

    public LoginResponse.UserInfo register(String username, String password, String nickname) {
        // 1. Check username uniqueness
        SysUser existing = sysUserMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleted, 0)
        );
        if (existing != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        // 2. Password strength
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException("密码至少8位，需包含字母和数字");
        }

        // 3. Encode password
        String encodedPassword = passwordEncoder.encode(password);

        // 4. Programmatic transaction
        SysUser newUser = transactionTemplate.execute(status -> {
            SysUser user = new SysUser();
            user.setUsername(username);
            user.setPassword(encodedPassword);
            user.setNickname(nickname != null ? nickname : username);
            user.setStatus(1);
            sysUserMapper.insert(user);

            // Assign default USER role (role_id = 2, assuming 1=ADMIN, 2=USER)
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(2L);
            sysUserRoleMapper.insert(userRole);

            return user;
        });

        if (newUser == null) {
            throw new RuntimeException("注册失败");
        }

        return new LoginResponse.UserInfo(
            newUser.getId(),
            newUser.getUsername(),
            newUser.getNickname(),
            newUser.getEmail(),
            newUser.getAvatar(),
            List.of("USER"),
            Set.of()
        );
    }

    public LoginResponse refreshToken(String refreshToken) {
        // 1. Validate signature
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("无效的刷新令牌");
        }

        // 2. Check token type
        if (!"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            throw new IllegalArgumentException("不是刷新令牌");
        }

        // 3. Get userId from Redis
        Long userId = tokenCacheService.getUserIdByRefreshToken(refreshToken);
        if (userId == null) {
            throw new IllegalArgumentException("刷新令牌已过期或已吊销");
        }

        // 4. Check user status
        SysUser user = sysUserMapper.selectOne(
            new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, 0)
        );
        if (user == null || (user.getStatus() != null && user.getStatus() != 1)) {
            throw new IllegalArgumentException("用户状态异常");
        }

        String redisStatus = tokenCacheService.getUserStatus(userId);
        if ("disabled".equals(redisStatus) || "deleted".equals(redisStatus)) {
            throw new IllegalArgumentException("账号已被禁用");
        }

        // 5. Generate new tokens
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        List<String> roleNames = getRoleNames(roleIds);

        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, roleNames);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(userId);

        // 6. Revoke old refresh token (rotation)
        tokenCacheService.revokeRefreshToken(refreshToken);

        // 7. Store new tokens
        String tokenId = extractTokenId(newAccessToken);
        tokenCacheService.storeAccessToken(userId, tokenId, roleNames);
        tokenCacheService.storeRefreshToken(newRefreshToken, userId);

        // 8. Return new token pair
        return new LoginResponse(
            newAccessToken,
            newRefreshToken,
            "Bearer",
            jwtProperties.accessExpiration(),
            jwtProperties.refreshExpiration(),
            null
        );
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
            throw new IllegalArgumentException("用户不存在");
        }

        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        List<String> roleNames = getRoleNames(roleIds);

        Set<String> permissions = tokenCacheService.getUserPermissions(userId);
        if (permissions == null) {
            permissions = loadUserPermissions(userId);
        }

        return new LoginResponse.UserInfo(
            user.getId(),
            user.getUsername(),
            user.getNickname(),
            user.getEmail(),
            user.getAvatar(),
            roleNames,
            permissions
        );
    }

    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("旧密码错误");
        }

        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new IllegalArgumentException("新密码至少8位，需包含字母和数字");
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

    public Set<String> loadUserPermissions(Long userId) {
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        Set<String> permissions = new HashSet<>();

        for (Long roleId : roleIds) {
            // Check if ADMIN role
            SysRole role = sysRoleMapper.selectById(roleId);
            if (role != null && "ADMIN".equalsIgnoreCase(role.getRoleName())) {
                permissions = new HashSet<>(Set.of("*:*"));
                break;
            }

            var perms = sysRolePermissionMapper.selectPermissionsByRoleId(roleId);
            for (var p : perms) {
                if (p.getResourceKey() != null) {
                    permissions.add(p.getResourceKey());
                }
            }
        }

        tokenCacheService.cacheUserPermissions(userId, permissions);
        return permissions;
    }

    private List<String> getRoleNames(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return roleIds.stream()
            .map(sysRoleMapper::selectById)
            .filter(Objects::nonNull)
            .map(SysRole::getRoleName)
            .collect(Collectors.toList());
    }

    private String extractTokenId(String token) {
        // Use a hash of the token as the tokenId for Redis key uniqueness
        return Integer.toHexString(token.hashCode());
    }
}
