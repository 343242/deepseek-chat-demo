package com.demo.deepseekchat.model.dto;

/**
 * 聊天响应 DTO（阻塞式）
 *
 * @param model         使用的模型 ID
 * @param content       模型回复内容
 * @param conversationId 对话 ID
 */
public record ChatResponse(
    String model,
    String content,
    String conversationId
) {}
