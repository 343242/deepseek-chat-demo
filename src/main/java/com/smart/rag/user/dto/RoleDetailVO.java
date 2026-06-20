package com.smart.rag.user.dto;

import java.util.List;

/**
 * 角色详情（含权限列表）
 */
public record RoleDetailVO(
    RoleVO role,
    List<PermissionVO> permissions
) {}
