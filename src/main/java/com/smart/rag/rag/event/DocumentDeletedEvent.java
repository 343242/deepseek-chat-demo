package com.smart.rag.rag.event;

/**
 * 文档删除事件
 * <p>
 * 由 {@link com.smart.rag.rag.service.impl.DocumentLifecycleService#cascadeDelete}
 * 在数据库逻辑删除后发布。语义：该文档已不可用，下游（如版本替换内存加速层
 * {@code pendingSupersede}）可据此清理相关状态。
 *
 * @param documentId 文档 ID
 */
public record DocumentDeletedEvent(
    Long documentId
) {}
