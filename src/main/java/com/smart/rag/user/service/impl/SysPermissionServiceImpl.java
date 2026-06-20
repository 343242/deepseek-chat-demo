package com.smart.rag.user.service.impl;

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

    // ==================== Entity → VO 转换 ====================

    private static PermissionVO toPermissionVO(SysPermission p) {
        return new PermissionVO(p.getId(), p.getPermissionName(), p.getPermissionDesc(),
                p.getResourceType(), p.getResourceKey(), p.getParentId(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
