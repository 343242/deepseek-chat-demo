package com.smart.rag.infrastructure.agent.workspace;

import java.util.Map;

/**
 * 检索结果 DTO — Tool Workspace 中的文档表示
 *
 * @param docId         文档 ID
 * @param content       文档内容
 * @param score         相关性分数
 * @param source        来源 Tool 名称（如 hybridSearch）
 * @param subQueryIndex 关联的子问题索引（-1 表示未关联）
 * @param metadata      文档元信息（文件名、页码等）
 */
public record RetrievedDocument(
    String docId,
    String content,
    double score,
    String source,
    int subQueryIndex,
    Map<String, Object> metadata
) {}
