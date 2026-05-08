package com.demo.chat.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建角色请求
 */
public record CreateRoleRequest(
    @NotBlank(message = "角色名不能为空")
    @Size(min = 2, max = 50, message = "角色名长度必须为2到50个字符")
    String roleName,

    @Size(max = 200, message = "角色描述最多200个字符")
    String roleDesc
) {}
