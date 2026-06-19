package com.smart.rag.rag.event;

/**
 * ETL 处理失败事件
 * <p>
 * 由 {@link com.smart.rag.rag.etl.EtlStatusManager#failDocument} 在文档状态落库为
 * {@code FAILED} 后发布（独立事务提交之后）。语义：该文档已确定不会走向 completed，
 * 下游（如版本替换内存加速层 {@code pendingSupersede}）可据此清理相关状态。
 * <p>
 * 仅对应 FAILED 终态；{@code VECTOR_FAILED}（向量化失败但 BM25 可用）不发此事件，
 * 因其必定发生在 {@link com.smart.rag.rag.event.EtlCompletedEvent} 之后，加速层已被清理。
 *
 * @param documentId   文档 ID
 * @param errorMessage 错误信息（截断后的诊断信息，可为 {@code null}）
 */
public record EtlFailedEvent(
    Long documentId,
    String errorMessage
) {}
