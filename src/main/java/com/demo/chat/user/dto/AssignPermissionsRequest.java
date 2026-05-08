package com.demo.chat.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AssignPermissionsRequest(
    @NotEmpty(message = "权限列表不能为空")
    @Size(max = 50, message = "单次最多分配50个权限")
    List<Long> permissionIds
) {}
