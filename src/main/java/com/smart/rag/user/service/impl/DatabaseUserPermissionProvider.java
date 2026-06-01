package com.smart.rag.user.service.impl;

import com.smart.rag.infrastructure.web.auth.UserPermissionProvider;
import com.smart.rag.infrastructure.web.service.TokenCacheService;
import com.smart.rag.user.mapper.SysRolePermissionMapper;
import com.smart.rag.user.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DatabaseUserPermissionProvider implements UserPermissionProvider {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final TokenCacheService tokenCacheService;

    public DatabaseUserPermissionProvider(
            SysUserRoleMapper sysUserRoleMapper,
            SysRolePermissionMapper sysRolePermissionMapper,
            TokenCacheService tokenCacheService
    ) {
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRolePermissionMapper = sysRolePermissionMapper;
        this.tokenCacheService = tokenCacheService;
    }

    @Override
    public Set<String> loadUserPermissions(Long userId) {
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            tokenCacheService.cacheUserPermissions(userId, Set.of());
            return Set.of();
        }

        Set<String> permissions = new HashSet<>();
        var permissionRecords = sysRolePermissionMapper.selectPermissionsByRoleIds(roleIds);
        for (var permission : permissionRecords) {
            if (permission.getPermissionName() != null) {
                permissions.add(permission.getPermissionName());
            }
        }

        tokenCacheService.cacheUserPermissions(userId, permissions);
        return permissions;
    }
}
