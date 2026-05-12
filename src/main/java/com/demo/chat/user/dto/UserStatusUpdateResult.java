package com.demo.chat.user.dto;

/**
 * 用户状态更新结果
 */
public record UserStatusUpdateResult(
    Long userId,
    Integer status,
    String message
) {}
