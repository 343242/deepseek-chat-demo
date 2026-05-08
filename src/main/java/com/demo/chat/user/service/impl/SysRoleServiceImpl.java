package com.demo.chat.user.service.impl;

import com.demo.chat.exception.BusinessException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.chat.security.service.TokenCacheService;
import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.entity.SysRole;
import com.demo.chat.user.entity.SysRolePermission;
import com.demo.chat.user.entity.SysUserRole;
import com.demo.chat.user.mapper.SysPermissionMapper;
import com.demo.chat.user.mapper.SysRoleMapper;
import com.demo.chat.user.mapper.SysRolePermissionMapper;
import com.demo.chat.user.mapper.SysUserRoleMapper;
import com.demo.chat.user.service.SysRoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SysRoleServiceImpl implements SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final TokenCacheService tokenCacheService;
    private final TransactionTemplate transactionTemplate;

    public SysRoleServiceImpl(SysRoleMapper roleMapper,
                          SysRolePermissionMapper rolePermissionMapper,
                          SysPermissionMapper permissionMapper,
                          SysUserRoleMapper userRoleMapper,
                          TokenCacheService tokenCacheService,
                          TransactionTemplate transactionTemplate) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.tokenCacheService = tokenCacheService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<SysRole> listRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getDeleted, 0)
                .orderByAsc(SysRole::getId));
    }

    @Override
    public Map<String, Object> getRoleDetail(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        List<SysPermission> permissions = rolePermissionMapper.selectPermissionsByRoleId(roleId);
        return Map.of("role", role, "permissions", permissions);
    }

    @Override
    public SysRole createRole(String roleName, String roleDesc) {
        // Check uniqueness
        SysRole existing = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleName, roleName)
                .eq(SysRole::getDeleted, 0));
        if (existing != null) {
            throw new BusinessException("角色名已存在");
        }

        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleDesc(roleDesc);
        role.setStatus(1);
        roleMapper.insert(role);
        return role;
    }

    @Override
    public SysRole updateRole(Long roleId, String roleDesc) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        role.setRoleDesc(roleDesc);
        roleMapper.updateById(role);
        return role;
    }

    @Override
    public void deleteRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 1. Find all users with this role
        List<Long> userIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
                .stream().map(SysUserRole::getUserId).toList();

        // 2. Evict permission cache for affected users
        for (Long userId : userIds) {
            tokenCacheService.evictUserPermissions(userId);
        }

        // 3. Delete role-permission associations
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, roleId));

        // 4. Delete user-role associations
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getRoleId, roleId));

        // 5. Logical delete role
        roleMapper.deleteById(roleId);
    }

    @Override
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 去重
        List<Long> uniquePermIds = permissionIds.stream().distinct().toList();

        // 存在性校验
        List<SysPermission> existingPerms = permissionMapper.selectBatchIds(uniquePermIds);
        if (existingPerms.size() != uniquePermIds.size()) {
            Set<Long> found = existingPerms.stream().map(SysPermission::getId).collect(Collectors.toSet());
            List<Long> missing = uniquePermIds.stream().filter(pid -> !found.contains(pid)).toList();
            throw new BusinessException("权限不存在: " + missing);
        }

        transactionTemplate.executeWithoutResult(status -> {
            // Delete old associations
            rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                    .eq(SysRolePermission::getRoleId, roleId));

            // Insert new associations
            for (Long permId : uniquePermIds) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(permId);
                rolePermissionMapper.insert(rp);
            }
        });

        // Find all users with this role and evict their permission cache
        List<Long> userIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
                .stream().map(SysUserRole::getUserId).toList();

        for (Long userId : userIds) {
            tokenCacheService.evictUserPermissions(userId);
        }
    }

    @Override
    public List<SysPermission> getRolePermissions(Long roleId) {
        return rolePermissionMapper.selectPermissionsByRoleId(roleId);
    }
}
