package com.demo.chat.user.service;

import com.demo.chat.exception.BusinessException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.mapper.SysPermissionMapper;
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
        // Check uniqueness — both permissionName and resourceKey must be unique
        SysPermission existingByName = permissionMapper.selectOne(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getPermissionName, permissionName)
                .eq(SysPermission::getDeleted, 0));
        if (existingByName != null) {
            throw new BusinessException("权限名称已存在: " + permissionName);
        }

        SysPermission existingByKey = permissionMapper.selectOne(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getResourceKey, resourceKey)
                .eq(SysPermission::getDeleted, 0));
        if (existingByKey != null) {
            throw new BusinessException("权限标识已存在: " + resourceKey);
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
            throw new BusinessException("权限不存在");
        }
        // Logical delete
        permissionMapper.deleteById(permissionId);
    }
}
