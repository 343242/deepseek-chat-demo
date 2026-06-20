package com.smart.rag.user.service.impl;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;
import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.user.dto.PermissionVO;
import com.smart.rag.user.entity.SysPermission;
import com.smart.rag.user.mapper.SysPermissionMapper;
import com.smart.rag.user.service.SysPermissionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysPermissionServiceImpl implements SysPermissionService {

    private final SysPermissionMapper permissionMapper;

    public SysPermissionServiceImpl(SysPermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    @Override
    public List<PermissionVO> listPermissions() {
        return permissionMapper.selectAllOrdered().stream()
                .map(SysPermissionServiceImpl::toPermissionVO)
                .toList();
    }

    @Override
    public PermissionVO createPermission(String permissionName, String permissionDesc,
                                          String resourceType, String resourceKey) {
        permissionMapper.selectByPermissionName(permissionName)
                .ifPresent(existing -> { throw new ClientException(ClientErrorCode.PERMISSION_NAME_EXISTS, "权限名称已存在: " + permissionName); });

        permissionMapper.selectByResourceKey(resourceKey)
                .ifPresent(existing -> { throw new ClientException(ClientErrorCode.PERMISSION_KEY_EXISTS, "权限标识已存在: " + resourceKey); });

        SysPermission perm = new SysPermission();
        perm.setPermissionName(permissionName);
        perm.setPermissionDesc(permissionDesc);
        perm.setResourceType(resourceType);
        perm.setResourceKey(resourceKey);
        perm.setStatus(1);
        permissionMapper.insert(perm);
        return toPermissionVO(perm);
    }

    @Override
    public void deletePermission(Long permissionId) {
        SysPermission perm = permissionMapper.selectById(permissionId);
        if (perm == null) {
            throw new ServiceException(ServiceErrorCode.PERMISSION_NOT_FOUND);
        }
        permissionMapper.deleteById(permissionId);
    }

    // ==================== Entity → VO 转换 ====================

    private static PermissionVO toPermissionVO(SysPermission p) {
        return new PermissionVO(p.getId(), p.getPermissionName(), p.getPermissionDesc(),
                p.getResourceType(), p.getResourceKey(), p.getParentId(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
