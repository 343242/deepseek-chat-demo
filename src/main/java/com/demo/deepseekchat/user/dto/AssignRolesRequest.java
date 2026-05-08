package com.demo.deepseekchat.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AssignRolesRequest(
    @NotEmpty(message = "角色列表不能为空")
    @Size(max = 20, message = "单次最多分配20个角色")
    List<Long> roleIds
) {}
