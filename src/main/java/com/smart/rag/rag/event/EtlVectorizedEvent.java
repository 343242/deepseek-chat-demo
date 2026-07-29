package com.smart.rag.rag.event;

import org.jspecify.annotations.Nullable;

/**
 * ETL 向量化完成事件（FastTrack 路径）
 * <p>
 * 在 FastTrackStrategy.asyncVectorize 中 loader.load(chunks) 成功后发布，
 * 触发实体索引构建。
 *
 * @param documentId 文档 ID
 * @param userId     用户 ID
 * @param teamId     团队 ID（null = 个人文档）
 */
public record EtlVectorizedEvent(
    Long documentId,
    Long userId,
    @Nullable Long teamId
) {}
