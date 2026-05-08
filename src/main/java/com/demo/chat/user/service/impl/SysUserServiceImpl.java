package com.demo.chat.user.service.impl;

import com.demo.chat.exception.BusinessException;
import com.demo.chat.user.enums.UserStatus;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.chat.security.service.TokenCacheService;
import com.demo.chat.user.dto.AssignRolesRequest;
import com.demo.chat.user.dto.LoginResponse;
import com.demo.chat.user.dto.UserUpdateRequest;
import com.demo.chat.user.entity.SysRole;
import com.demo.chat.user.entity.SysUser;
import com.demo.chat.user.entity.SysUserRole;
import com.demo.chat.user.mapper.SysRoleMapper;
import com.demo.chat.user.mapper.SysUserMapper;
import com.demo.chat.user.mapper.SysUserRoleMapper;
import com.demo.chat.user.service.AuthService;
import com.demo.chat.user.service.SysUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户管理 Service — UserController 的写操作逻辑下沉到此处
 */
@Service
public class SysUserServiceImpl implements SysUserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final TransactionTemplate transactionTemplate;
    private final TokenCacheService tokenCacheService;
    private final AuthService authService;

    public SysUserServiceImpl(SysUserMapper userMapper,
                          SysUserRoleMapper userRoleMapper,
                          SysRoleMapper roleMapper,
                          TransactionTemplate transactionTemplate,
                          TokenCacheService tokenCacheService,
                          AuthService authService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.transactionTemplate = transactionTemplate;
        this.tokenCacheService = tokenCacheService;
        this.authService = authService;
    }

    @Override
    public Map<String, Object> listUsers(int page, int size, String keyword) {
        Page<SysUser> pageReq = new Page<>(page, size);
        // 分页查询含条件，保留 selectPage（MyBatis-Plus 内置分页机制）
        Page<SysUser> result = userMapper.selectPage(pageReq,
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getDeleted, 0)
                        .and(keyword != null && !keyword.isBlank(), w ->
                                w.like(SysUser::getUsername, keyword)
                                        .or().like(SysUser::getNickname, keyword))
                        .orderByDesc(SysUser::getCreatedAt));

        List<Map<String, Object>> content = result.getRecords().stream()
                .map(SysUserServiceImpl::toSafeMap)
                .collect(Collectors.toList());

        return Map.of(
                "content", content,
                "page", page,
                "size", size,
                "total", result.getTotal(),
                "totalPages", result.getPages()
        );
    }

    @Override
    public LoginResponse.UserInfo getUser(Long id) {
        return authService.getCurrentUser(id);
    }

    @Override
    public LoginResponse.UserInfo updateUser(Long id, UserUpdateRequest request) {
        return authService.updateProfile(id, request);
    }

    @Override
    public Map<String, Object> updateUserStatus(Long id, Integer status) {
        if (!UserStatus.isValid(status)) {
            throw new BusinessException("无效的用户状态，仅支持 0(禁用) 和 1(启用)");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setStatus(status);
        userMapper.updateById(user);

        if (status == UserStatus.DISABLED.code) {
            authService.revokeAllUserTokens(id);
        } else if (status == UserStatus.ENABLED.code) {
            tokenCacheService.clearUserStatus(id);
        }

        return Map.of("userId", id, "status", status, "message", status == UserStatus.ENABLED.code ? "已启用" : "已禁用");
    }

    @Override
    public Map<String, Object> assignRoles(Long id, AssignRolesRequest request) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        List<Long> uniqueRoleIds = request.roleIds().stream().distinct().toList();

        List<SysRole> existingRoles = roleMapper.selectBatchIds(uniqueRoleIds);
        if (existingRoles.size() != uniqueRoleIds.size()) {
            Set<Long> found = existingRoles.stream().map(SysRole::getId).collect(Collectors.toSet());
            List<Long> missing = uniqueRoleIds.stream().filter(rid -> !found.contains(rid)).toList();
            throw new BusinessException("角色不存在: " + missing);
        }

        transactionTemplate.executeWithoutResult(status -> {
            userRoleMapper.deleteByUserId(id);

            List<SysUserRole> bindings = uniqueRoleIds.stream()
                    .map(roleId -> {
                        SysUserRole userRole = new SysUserRole();
                        userRole.setUserId(id);
                        userRole.setRoleId(roleId);
                        return userRole;
                    })
                    .toList();
            if (!bindings.isEmpty()) {
                userRoleMapper.batchInsert(bindings);
            }
        });

        tokenCacheService.evictUserPermissions(id);

        return Map.of("userId", id, "roles", uniqueRoleIds, "message", "角色已更新");
    }

    @Override
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
