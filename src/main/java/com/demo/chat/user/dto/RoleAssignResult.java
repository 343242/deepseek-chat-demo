package com.demo.chat.user.dto;

import java.util.List;

/**
 * 角色分配结果
 */
public record RoleAssignResult(
    Long userId,
    List<Long> roleIds,
    String message
) {}
