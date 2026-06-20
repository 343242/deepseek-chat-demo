package com.smart.rag.user.dto;

import java.time.OffsetDateTime;

/**
 * 权限视图对象（管理后台）。替代直返 {@code SysPermission} Entity，剔除 deleted 等内部字段。
 */
public record PermissionVO(
        Long id,
        String permissionName,
        String permissionDesc,
        String resourceType,
        String resourceKey,
        Long parentId,
        Integer status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
