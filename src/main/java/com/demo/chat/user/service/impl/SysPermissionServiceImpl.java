package com.demo.chat.user.service.impl;

import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.mapper.SysPermissionMapper;
import com.demo.chat.user.service.SysPermissionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysPermissionServiceImpl implements SysPermissionService {

    private final SysPermissionMapper permissionMapper;

    public SysPermissionServiceImpl(SysPermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    @Override
    public List<SysPermission> listPermissions() {
        return permissionMapper.selectAllOrdered();
    }

    @Override
    public SysPermission createPermission(String permissionName, String permissionDesc,
                                           String resourceType, String resourceKey) {
        permissionMapper.selectByPermissionName(permissionName)
                .ifPresent(existing -> { throw new BusinessException(ErrorCode.PERMISSION_NAME_EXISTS, "权限名称已存在: " + permissionName); });

        permissionMapper.selectByResourceKey(resourceKey)
                .ifPresent(existing -> { throw new BusinessException(ErrorCode.PERMISSION_KEY_EXISTS, "权限标识已存在: " + resourceKey); });

        SysPermission perm = new SysPermission();
        perm.setPermissionName(permissionName);
        perm.setPermissionDesc(permissionDesc);
        perm.setResourceType(resourceType);
        perm.setResourceKey(resourceKey);
        perm.setStatus(1);
        permissionMapper.insert(perm);
        return perm;
    }

    @Override
    public void deletePermission(Long permissionId) {
        SysPermission perm = permissionMapper.selectById(permissionId);
        if (perm == null) {
            throw new BusinessException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        permissionMapper.deleteById(permissionId);
    }
}
