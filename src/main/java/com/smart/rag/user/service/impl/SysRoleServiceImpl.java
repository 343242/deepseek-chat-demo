package com.smart.rag.user.service.impl;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.security.service.TokenCacheService;
import com.smart.rag.user.entity.SysPermission;
import com.smart.rag.user.entity.SysRole;
import com.smart.rag.user.entity.SysRolePermission;
import com.smart.rag.user.dto.RoleDetailVO;
import com.smart.rag.user.mapper.SysPermissionMapper;
import com.smart.rag.user.mapper.SysRoleMapper;
import com.smart.rag.user.mapper.SysRolePermissionMapper;
import com.smart.rag.user.mapper.SysUserRoleMapper;
import com.smart.rag.user.service.SysRoleService;
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
        return roleMapper.selectAllOrdered();
    }

    @Override
    public RoleDetailVO getRoleDetail(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        List<SysPermission> permissions = rolePermissionMapper.selectPermissionsByRoleId(roleId);
        return new RoleDetailVO(role, permissions);
    }

    @Override
    public SysRole createRole(String roleName, String roleDesc) {
        roleMapper.selectByRoleName(roleName)
                .ifPresent(existing -> { throw new BusinessException(ErrorCode.ROLE_NAME_EXISTS); });

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
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        role.setRoleDesc(roleDesc);
        roleMapper.updateById(role);
        return role;
    }

    @Override
    public void deleteRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }

        List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);
        for (Long userId : userIds) {
            tokenCacheService.evictUserPermissions(userId);
        }

        rolePermissionMapper.deleteByRoleId(roleId);
        userRoleMapper.deleteByRoleId(roleId);
        roleMapper.deleteById(roleId);
    }

    @Override
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }

        List<Long> uniquePermIds = permissionIds.stream().distinct().toList();

        List<SysPermission> existingPerms = permissionMapper.selectByIds(uniquePermIds);
        if (existingPerms.size() != uniquePermIds.size()) {
            Set<Long> found = existingPerms.stream().map(SysPermission::getId).collect(Collectors.toSet());
            List<Long> missing = uniquePermIds.stream().filter(pid -> !found.contains(pid)).toList();
            throw new BusinessException(ErrorCode.PERMISSION_NOT_FOUND, "权限不存在: " + missing);
        }

        transactionTemplate.executeWithoutResult(status -> {
            rolePermissionMapper.deleteByRoleId(roleId);

            List<SysRolePermission> bindings = uniquePermIds.stream()
                    .map(permId -> {
                        SysRolePermission rp = new SysRolePermission();
                        rp.setRoleId(roleId);
                        rp.setPermissionId(permId);
                        return rp;
                    })
                    .toList();
            if (!bindings.isEmpty()) {
                rolePermissionMapper.batchInsert(bindings);
            }
        });

        List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);
        for (Long userId : userIds) {
            tokenCacheService.evictUserPermissions(userId);
        }
    }

    @Override
    public List<SysPermission> getRolePermissions(Long roleId) {
        return rolePermissionMapper.selectPermissionsByRoleId(roleId);
    }
}
