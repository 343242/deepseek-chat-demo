package com.demo.chat.rag.event;

import org.jspecify.annotations.Nullable;

/**
 * 文档记录创建事件
 *
 * @param documentId         新文档 ID
 * @param replaceDocumentId  用户指定的替换目标文档 ID（null = 新文档）
 * @param userId             当前用户 ID
 * @param teamId             团队 ID（null = 个人文档）
 */
public record DocumentCreatedEvent(
    Long documentId,
    @Nullable Long replaceDocumentId,
    Long userId,
    @Nullable Long teamId
) {}
