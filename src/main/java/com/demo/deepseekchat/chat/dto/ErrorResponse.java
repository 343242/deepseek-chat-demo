package com.demo.deepseekchat.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应 DTO
 *
 * @param error   错误类型标识（如 rate_limit_exceeded）
 * @param message 友好消息
 * @param status  HTTP 状态码
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String error,
    String message,
    int status
) {}
