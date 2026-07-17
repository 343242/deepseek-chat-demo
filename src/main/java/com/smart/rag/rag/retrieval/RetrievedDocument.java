package com.smart.rag.rag.retrieval;

import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * 检索结果 DTO — 检索工作区中的文档表示（agent + chat 双路径共享）。
 * <p>
 * 归属于 rag.retrieval 包：作为检索结果的统一文档表示，供 agent 工具链（ToolWorkspace）
 * 与 chat 检索路径（ChatReferenceCollector）共享，避免 chat 反向依赖 agent.workspace。
 *
 * @param chunkId       vector_store.id（chunk 级），原 docId，重命名消除"docId 实为 chunkId"歧义
 * @param documentId    所属文档 ID（metadata.documentId 提升为一等字段）
 * @param fileName      文件名（metadata.fileName 提升为一等字段；缺失降级 documentId / "未知"）
 * @param page          页码（metadata.page_number，可空）
 * @param refNumber     收集器分配的稳定编号 [n]，0=未分配
 * @param content       文档内容
 * @param score         相关性分数
 * @param source        来源 Tool 名称（如 hybridSearch）
 * @param subQueryIndex 关联的子问题索引（-1 表示未关联）
 * @param metadata      文档元信息（documentId/userId/teamId/fileName/page_number/retrievalSource 等）
 */
public record RetrievedDocument(
    String chunkId,
    String documentId,
    String fileName,
    Integer page,
    int refNumber,
    String content,
    double score,
    String source,
    int subQueryIndex,
    Map<String, Object> metadata
) {

    /** 占位文件名：metadata 缺 fileName 且无 documentId 兜底时使用 */
    private static final String UNKNOWN_FILE_NAME = "未知";

    /**
     * 从 Spring AI {@link Document} 统一构造：提取 chunkId/documentId/fileName/page，
     * content/score/metadata 取自文档；source/subQueryIndex 留默认（null/-1），refNumber=0（由收集器赋值）。
     * <p>
     * metadata 返回可变副本，调用方可在其上追加键（retrievalSource/sourceDocId 等）。
     */
    public static RetrievedDocument from(Document d) {
        Map<String, Object> metadata = d.getMetadata() != null
            ? new HashMap<>(d.getMetadata()) : new HashMap<>();
        String documentId = metaStr(metadata, "documentId");
        String fileName = metaStr(metadata, "fileName");
        if ((fileName == null || fileName.isBlank()) && metaStr(metadata, "source") != null) {
            // 降级 1：source 通常是上传文件名（opendataloader 等解析器写 source 不写 fileName）
            String src = metaStr(metadata, "source");
            fileName = src.contains("/") ? src.substring(src.lastIndexOf('/') + 1) : src;
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = (documentId != null && !documentId.isBlank()) ? documentId : UNKNOWN_FILE_NAME;
        }
        Integer page = metaInt(metadata, "page_number");
        double score = d.getScore() != null ? d.getScore() : 0.0;
        return new RetrievedDocument(
            d.getId(), documentId, fileName, page, 0,
            d.getText(), score, null, -1, metadata);
    }

    private static String metaStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static Integer metaInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 分配/重设稳定编号（不可变重建） */
    public RetrievedDocument withRefNumber(int n) {
        return new RetrievedDocument(chunkId, documentId, fileName, page, n,
            content, score, source, subQueryIndex, metadata);
    }

    /** 重设来源 Tool 名（不可变重建） */
    public RetrievedDocument withSource(String src) {
        return new RetrievedDocument(chunkId, documentId, fileName, page, refNumber,
            content, score, src, subQueryIndex, metadata);
    }

    /** 重设分数（不可变重建，BM25 强制 0.0 / rerank 取 metadata 等） */
    public RetrievedDocument withScore(double s) {
        return new RetrievedDocument(chunkId, documentId, fileName, page, refNumber,
            content, s, source, subQueryIndex, metadata);
    }

    /** 重设子问题索引（不可变重建） */
    public RetrievedDocument withSubQueryIndex(int idx) {
        return new RetrievedDocument(chunkId, documentId, fileName, page, refNumber,
            content, score, source, idx, metadata);
    }

    /** 重设内容（不可变重建，内容截断用） */
    public RetrievedDocument withContent(String c) {
        return new RetrievedDocument(chunkId, documentId, fileName, page, refNumber,
            c, score, source, subQueryIndex, metadata);
    }
}
