package com.smart.rag.user.dto;

import java.util.List;

/**
 * 权限分配结果（替代手搓 Map 响应，与模块内其他 Result record 风格统一）
 */
public record AssignPermissionsResult(
        Long roleId,
        List<Long> permissionIds,
        String message
) {}
