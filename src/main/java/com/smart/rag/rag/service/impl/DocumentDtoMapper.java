package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.dto.ChunkDTO;
import com.smart.rag.rag.dto.DocumentDTO;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.DocumentPreviewPolicy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 文档 DTO 映射器（单一职责）
 * <p>
 * 封装 RagDocument / VectorStoreRow → DTO 的映射及 metadata 中 documentId 的解析。
 * 从 DocumentApplicationServiceImpl 中提取，符合 SRP。
 */
@Component
public class DocumentDtoMapper {

    private final DocumentPreviewPolicy previewPolicy;

    public DocumentDtoMapper(DocumentPreviewPolicy previewPolicy) {
        this.previewPolicy = previewPolicy;
    }

    public DocumentDTO toDTO(RagDocument doc) {
        long fileSize = doc.getFileSize() != null ? doc.getFileSize() : 0L;
        return new DocumentDTO(
                doc.getId(),
                doc.getFileName(),
                doc.getFileSize(),
                doc.getMimeType(),
                doc.getChunkCount(),
                doc.getStatus(),
                doc.getErrorMessage(),
                doc.getUserId(),
                doc.getTeamId(),
                doc.getVersion(),
                doc.getSupersededBy(),
                doc.getDocumentGroupId(),
                doc.getCreateTime(),
                previewPolicy.previewable(doc.getMimeType(), fileSize)
        );
    }

    public ChunkDTO toChunkDTO(VectorStoreMapper.VectorStoreRow row) {
        Map<String, Object> metadata = row.metadata() != null ? row.metadata() : Map.of();
        Long documentId = parseDocumentId(metadata);
        String fileName = metadata.get("fileName") instanceof String s ? s : null;
        return new ChunkDTO(row.id(), row.content(), documentId, fileName, metadata);
    }

    /**
     * 从 metadata 解析 documentId（Long）。metadata 中 documentId 存为字符串。
     *
     * @return documentId；缺失或不可解析返回 null
     */
    public Long parseDocumentId(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get("documentId");
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
