package com.demo.chat.user.dto;

/**
 * 用户删除结果
 */
public record UserDeleteResult(
    Long userId,
    String message
) {}
