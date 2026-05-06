package com.demo.deepseekchat.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.deepseekchat.user.entity.SysPermission;
import com.demo.deepseekchat.user.mapper.SysPermissionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysPermissionService {

    private final SysPermissionMapper permissionMapper;

    public SysPermissionService(SysPermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    public List<SysPermission> listPermissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getDeleted, 0)
                .orderByAsc(SysPermission::getId));
    }

    public SysPermission createPermission(String permissionName, String permissionDesc,
                                           String resourceType, String resourceKey) {
        // Check uniqueness
        SysPermission existing = permissionMapper.selectOne(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getResourceKey, resourceKey)
                .eq(SysPermission::getDeleted, 0));
        if (existing != null) {
            throw new IllegalArgumentException("权限标识已存在: " + resourceKey);
        }

        SysPermission perm = new SysPermission();
        perm.setPermissionName(permissionName);
        perm.setPermissionDesc(permissionDesc);
        perm.setResourceType(resourceType);
        perm.setResourceKey(resourceKey);
        perm.setStatus(1);
        permissionMapper.insert(perm);
        return perm;
    }

    public void deletePermission(Long permissionId) {
        SysPermission perm = permissionMapper.selectById(permissionId);
        if (perm == null) {
            throw new IllegalArgumentException("权限不存在");
        }
        // Logical delete
        permissionMapper.deleteById(permissionId);
    }
}
