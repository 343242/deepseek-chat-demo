package com.smart.rag.rag.etl;

import org.springframework.ai.document.Document;

import java.time.Duration;
import java.util.List;

/**
 * 分块元数据填充器 — {@link StandardStrategy} 与 {@link FastTrackStrategy} 共享
 * <p>
 * 为每个 chunk 写入检索过滤所需的元数据（documentId / userId / teamId / fileName），
 * 消除两策略间重复的填充循环。
 */
public final class ChunkMetadataEnricher {

    /** ETL TaskScope 默认超时（Extract / Transform / Load 各阶段共用） */
    public static final Duration DEFAULT_SCOPE_TIMEOUT = Duration.ofMinutes(5);

    private ChunkMetadataEnricher() {
    }

    /**
     * 将 ETL 候选文档的标识信息写入每个 chunk 的 metadata。
     *
     * @param chunks     待填充的分块列表（原地修改）
     * @param documentId 文档 ID
     * @param userId     上传者用户 ID
     * @param teamId     团队 ID（可空，空时不写入 teamId 键）
     * @param fileName   源文件名（空/空白时回退为 documentId 字符串）
     */
    public static void enrich(List<Document> chunks,
                              Long documentId,
                              Long userId,
                              @org.jspecify.annotations.Nullable Long teamId,
                              @org.jspecify.annotations.Nullable String fileName) {
        String docIdStr = String.valueOf(documentId);
        String userIdStr = String.valueOf(userId);
        String teamIdStr = teamId != null ? String.valueOf(teamId) : null;
        String fileNameStr = (fileName != null && !fileName.isBlank()) ? fileName : docIdStr;
        for (Document chunk : chunks) {
            chunk.getMetadata().put("documentId", docIdStr);
            chunk.getMetadata().put("userId", userIdStr);
            chunk.getMetadata().put("fileName", fileNameStr);
            if (teamIdStr != null) {
                chunk.getMetadata().put("teamId", teamIdStr);
            }
        }
    }
}
