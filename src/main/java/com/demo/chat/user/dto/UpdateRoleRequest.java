package com.demo.chat.user.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新角色请求
 */
public record UpdateRoleRequest(
    @Size(max = 200, message = "角色描述最多200个字符")
    String roleDesc
) {}
