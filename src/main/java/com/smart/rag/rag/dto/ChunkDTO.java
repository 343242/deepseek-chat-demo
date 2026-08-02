package com.smart.rag.rag.dto;

import java.util.Map;

/**
 * 文档片段（chunk）DTO —— 供前端内容查看使用。
 *
 * <p>底层来自 {@code vector_store} 表（Spring AI 的 chunk 存储），每行对应一个切分后的片段。
 * 通过 {@code metadata->>'documentId'} 关联到 {@code rag_document}。
 *
 * @param id         chunk 在 vector_store 的 UUID（引用卡片 {@code chunkId} 即此值）
 * @param content    片段全文
 * @param documentId 所属文档 ID（从 metadata.documentId 解析）
 * @param fileName   源文件名（从 metadata.fileName）
 * @param metadata   完整 metadata（前端按需取字段，如 page / teamId 等）
 */
public record ChunkDTO(
        String id,
        String content,
        Long documentId,
        String fileName,
        Map<String, Object> metadata
) {}
