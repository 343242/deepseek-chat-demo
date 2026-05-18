package com.demo.chat.rag.event;

import org.jspecify.annotations.Nullable;

/**
 * ETL 处理成功事件
 *
 * @param documentId 文档 ID
 * @param userId     用户 ID
 * @param teamId     团队 ID（null = 个人文档）
 */
public record EtlCompletedEvent(
    Long documentId,
    Long userId,
    @Nullable Long teamId
) {}
