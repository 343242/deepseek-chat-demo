package com.smart.rag.rag.event;

import com.smart.rag.rag.etl.EtlStatus;
import org.jspecify.annotations.Nullable;

/**
 * 文档状态变更事件。
 * <p>
 * 由 {@link com.smart.rag.rag.etl.EtlStatusManager} 在文档状态落库（独立事务提交）后发布，
 * 驱动 SSE 实时推送：Spring 进程内事件 → {@code DocumentSseRelay} 广播到 Redis Pub/Sub →
 * 各实例转发给本地 SSE 连接。
 * <p>
 * 与 {@link EtlCompletedEvent}/{@link EtlFailedEvent}/{@link EtlVectorizedEvent} 并存——
 * 本事件面向 SSE 推送（统一覆盖所有状态流转，含中间态），后三者各有独立下游
 * （版本替换加速层等），互不干扰。
 *
 * @param documentId 文档 ID
 * @param userId     文档所有者（SSE 路由键，按 userId 推给上传者）
 * @param teamId     团队 ID（null = 个人文档；首版 SSE 按 userId 路由，此字段预留）
 * @param status     新状态
 */
public record DocumentStatusChangedEvent(
    Long documentId,
    Long userId,
    @Nullable Long teamId,
    EtlStatus status
) {}
