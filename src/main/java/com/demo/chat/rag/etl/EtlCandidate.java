package com.demo.chat.rag.etl;

/**
 * ETL 候选文档 — 路由策略的输入参数
 *
 * @param documentId 文档 ID（写入 metadata 时统一转为 String，格式: String.valueOf(documentId)）
 * @param bucket     MinIO bucket
 * @param objectKey  MinIO object key
 * @param fileName   文件名
 * @param mimeType   MIME 类型
 * @param fileSize   文件大小（字节）
 * @param userId     文档所有者 ID，用于向量库检索隔离
 */
public record EtlCandidate(
        Long documentId,
        String bucket,
        String objectKey,
        String fileName,
        String mimeType,
        long fileSize,
        Long userId
) {}
