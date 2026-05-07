package com.demo.deepseekchat.user.service;

import com.demo.deepseekchat.exception.BusinessException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.deepseekchat.security.service.TokenCacheService;
import com.demo.deepseekchat.user.dto.AssignRolesRequest;
import com.demo.deepseekchat.user.dto.LoginResponse;
import com.demo.deepseekchat.user.dto.UserUpdateRequest;
import com.demo.deepseekchat.user.entity.SysUser;
import com.demo.deepseekchat.user.entity.SysUserRole;
import com.demo.deepseekchat.user.mapper.SysUserMapper;
import com.demo.deepseekchat.user.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户管理 Service — UserController 的写操作逻辑下沉到此处
 */
@Service
public class SysUserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final TransactionTemplate transactionTemplate;
    private final TokenCacheService tokenCacheService;
    private final AuthService authService;

    public SysUserService(SysUserMapper userMapper,
                          SysUserRoleMapper userRoleMapper,
                          TransactionTemplate transactionTemplate,
                          TokenCacheService tokenCacheService,
                          AuthService authService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.transactionTemplate = transactionTemplate;
        this.tokenCacheService = tokenCacheService;
        this.authService = authService;
    }

    public Map<String, Object> listUsers(int page, int size, String keyword) {
        Page<SysUser> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .and(keyword != null && !keyword.isBlank(), w ->
                        w.like(SysUser::getUsername, keyword)
                                .or().like(SysUser::getNickname, keyword))
                .orderByDesc(SysUser::getCreatedAt);

        Page<SysUser> result = userMapper.selectPage(pageReq, wrapper);

        List<Map<String, Object>> content = result.getRecords().stream()
                .map(SysUserService::toSafeMap)
                .collect(Collectors.toList());

        return Map.of(
                "content", content,
                "page", page,
                "size", size,
                "total", result.getTotal(),
                "totalPages", result.getPages()
        );
    }

    public LoginResponse.UserInfo getUser(Long id) {
        return authService.getCurrentUser(id);
    }

    public LoginResponse.UserInfo updateUser(Long id, UserUpdateRequest request) {
        return authService.updateProfile(id, request);
    }

    public Map<String, Object> updateUserStatus(Long id, Integer status) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setStatus(status);
        userMapper.updateById(user);

        if (status == 0) {
            authService.revokeAllUserTokens(id);
        } else {
            tokenCacheService.clearUserStatus(id);
        }

        return Map.of("userId", id, "status", status, "message", status == 1 ? "已启用" : "已禁用");
    }

    public Map<String, Object> assignRoles(Long id, AssignRolesRequest request) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        transactionTemplate.executeWithoutResult(status -> {
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, id));

            if (request.roleIds() != null) {
                for (Long roleId : request.roleIds()) {
                    SysUserRole userRole = new SysUserRole();
                    userRole.setUserId(id);
                    userRole.setRoleId(roleId);
                    userRoleMapper.insert(userRole);
                }
            }
        });

        tokenCacheService.evictUserPermissions(id);

        return Map.of("userId", id, "roles", request.roleIds() != null ? request.roleIds() : List.of(), "message", "角色已更新");
    }

    public Map<String, Object> deleteUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        userMapper.deleteById(id);
        authService.revokeAllUserTokens(id);

        return Map.of("userId", id, "message", "用户已删除");
    }

    private static Map<String, Object> toSafeMap(SysUser user) {
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
