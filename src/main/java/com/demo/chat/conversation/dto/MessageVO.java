package com.demo.chat.conversation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息 VO（视图对象）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageVO(
    Long id,
    Long parentId,
    String role,
    String content,
    String status,
    String modelId,
    Boolean thinkingEnabled,
    Integer tokenUsage,
    Long durationMs,
    LocalDateTime createdAt,
    /** 子消息（分支，仅加载一层） */
    List<MessageVO> children
) {}
