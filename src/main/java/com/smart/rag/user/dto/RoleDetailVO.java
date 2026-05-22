package com.smart.rag.user.dto;

import com.smart.rag.user.entity.SysPermission;
import com.smart.rag.user.entity.SysRole;

import java.util.List;

/**
 * 角色详情（含权限列表）
 */
public record RoleDetailVO(
    SysRole role,
    List<SysPermission> permissions
) {}
