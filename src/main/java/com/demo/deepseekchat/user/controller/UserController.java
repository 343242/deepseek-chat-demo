package com.demo.deepseekchat.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.deepseekchat.security.service.TokenCacheService;
import com.demo.deepseekchat.user.dto.AssignRolesRequest;
import com.demo.deepseekchat.user.dto.LoginResponse;
import com.demo.deepseekchat.user.dto.UserUpdateRequest;
import com.demo.deepseekchat.user.entity.SysRole;
import com.demo.deepseekchat.user.entity.SysUser;
import com.demo.deepseekchat.user.entity.SysUserRole;
import com.demo.deepseekchat.user.mapper.SysRoleMapper;
import com.demo.deepseekchat.user.mapper.SysRolePermissionMapper;
import com.demo.deepseekchat.user.mapper.SysUserMapper;
import com.demo.deepseekchat.user.mapper.SysUserRoleMapper;
import com.demo.deepseekchat.user.mapper.SysPermissionMapper;
import com.demo.deepseekchat.user.service.AuthService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasAuthority('user:manage')")
public class UserController {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final AuthService authService;
    private final TokenCacheService tokenCacheService;
    private final TransactionTemplate transactionTemplate;

    public UserController(SysUserMapper userMapper,
                          SysUserRoleMapper userRoleMapper,
                          SysRoleMapper roleMapper,
                          SysPermissionMapper permissionMapper,
                          SysRolePermissionMapper rolePermissionMapper,
                          AuthService authService,
                          TokenCacheService tokenCacheService,
                          TransactionTemplate transactionTemplate) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.authService = authService;
        this.tokenCacheService = tokenCacheService;
        this.transactionTemplate = transactionTemplate;
    }

    @GetMapping
    public Map<String, Object> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {

        Page<SysUser> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .and(keyword != null && !keyword.isBlank(), w ->
                        w.like(SysUser::getUsername, keyword)
                                .or().like(SysUser::getNickname, keyword))
                .orderByDesc(SysUser::getCreatedAt);

        Page<SysUser> result = userMapper.selectPage(pageReq, wrapper);

        List<Map<String, Object>> content = result.getRecords().stream()
                .map(this::toSafeMap)
                .collect(Collectors.toList());

        return Map.of(
                "content", content,
                "page", page,
                "size", size,
                "total", result.getTotal(),
                "totalPages", result.getPages()
        );
    }

    @GetMapping("/{id}")
    public LoginResponse.UserInfo getUser(@PathVariable Long id) {
        return authService.getCurrentUser(id);
    }

    @PatchMapping("/{id}")
    public LoginResponse.UserInfo updateUser(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return authService.updateProfile(id, request);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> updateUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        user.setStatus(status);
        userMapper.updateById(user);

        if (status == 0) {
            authService.revokeAllUserTokens(id);
        } else {
            // Re-enable: clear disabled status in Redis
            tokenCacheService.clearUserStatus(id);
        }

        return Map.of("userId", id, "status", status, "message", status == 1 ? "已启用" : "已禁用");
    }

    @PatchMapping("/{id}/roles")
    public Map<String, Object> assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest request) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        transactionTemplate.executeWithoutResult(status -> {
            // Delete old roles
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, id));

            // Insert new roles
            if (request.roleIds() != null) {
                for (Long roleId : request.roleIds()) {
                    SysUserRole userRole = new SysUserRole();
                    userRole.setUserId(id);
                    userRole.setRoleId(roleId);
                    userRoleMapper.insert(userRole);
                }
            }
        });

        // Clear permission cache for this user
        tokenCacheService.evictUserPermissions(id);

        return Map.of("userId", id, "roles", request.roleIds() != null ? request.roleIds() : List.of(), "message", "角色已更新");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // Logical delete (MyBatis-Plus @TableLogic)
        userMapper.deleteById(id);

        // Revoke all tokens and clear cache
        authService.revokeAllUserTokens(id);

        return Map.of("userId", id, "message", "用户已删除");
    }

    private Map<String, Object> toSafeMap(SysUser user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("nickname", user.getNickname());
        map.put("email", user.getEmail());
        map.put("phone", user.getPhone());
        map.put("avatar", user.getAvatar());
        map.put("status", user.getStatus());
        map.put("createdAt", user.getCreatedAt());
        map.put("updatedAt", user.getUpdatedAt());
        return map;
    }
}
