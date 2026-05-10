package com.demo.chat.rag.etl;

/**
 * ETL 处理结果 — 路由策略的输出
 *
 * @param documentId   文档 ID
 * @param status       最终状态（COMPLETED / FAILED / VECTOR_FAILED）
 * @param chunkCount   分块数量（快速通道异步完成前可能为 0）
 * @param errorMessage 错误信息（失败时有值）
 */
public record EtlResult(
        Long documentId,
        String status,
        int chunkCount,
        String errorMessage
) {
    public static EtlResult success(Long documentId, int chunkCount) {
        return new EtlResult(documentId, "COMPLETED", chunkCount, null);
    }

    public static EtlResult failed(Long documentId, String errorMessage) {
        return new EtlResult(documentId, "FAILED", 0, errorMessage);
    }
}
