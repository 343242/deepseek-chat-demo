package com.smart.rag.user.dto;

import java.time.OffsetDateTime;

/**
 * 角色视图对象（管理后台）。替代直返 {@code SysRole} Entity，剔除 deleted 等内部字段。
 */
public record RoleVO(
        Long id,
        String roleName,
        String roleDesc,
        Integer status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
