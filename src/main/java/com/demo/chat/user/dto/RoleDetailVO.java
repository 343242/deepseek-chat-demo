package com.demo.chat.user.dto;

import com.demo.chat.user.entity.SysPermission;
import com.demo.chat.user.entity.SysRole;

import java.util.List;

/**
 * 角色详情（含权限列表）
 */
public record RoleDetailVO(
    SysRole role,
    List<SysPermission> permissions
) {}
