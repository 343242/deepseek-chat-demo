package com.demo.chat.rag.service.impl;

import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.parser.DocumentParser;
import com.demo.chat.rag.parser.DocumentParserFactory;
import com.demo.chat.rag.service.DocumentChunkService;
import com.demo.chat.rag.service.EtlPipelineService;
import com.demo.chat.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ETL Pipeline 编排服务实现
 * <p>
 * 流程：Extract(从 MinIO 下载 → Parser 解析) → Transform(分块) → Load(预留)
 * 每个阶段更新 rag_document 表的 status 字段。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtlPipelineServiceImpl implements EtlPipelineService {

    private final FileStorageService fileStorageService;
    private final DocumentParserFactory parserFactory;
    private final DocumentChunkService chunkService;
    private final RagDocumentMapper ragDocumentMapper;

    @Override
    @Transactional
    public int execute(Long documentId, String bucket, String objectKey, String fileName, String mimeType) {
        log.info("ETL pipeline started for document: id={}, file={}, mime={}", documentId, fileName, mimeType);

        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new IllegalArgumentException("Document not found: " + documentId);
        }

        try {
            // === Extract ===
            updateStatus(documentId, "PARSING");
            Resource fileResource = fileStorageService.download(bucket, objectKey);
            DocumentParser parser = parserFactory.getParser(mimeType);
            List<Document> rawDocuments = parser.parse(fileResource, mimeType);
            log.info("Extracted {} segments from {}", rawDocuments.size(), fileName);

            // === Transform (Split) ===
            updateStatus(documentId, "CHUNKING");
            List<Document> chunks = chunkService.chunk(rawDocuments, fileName);

            // === Load (Phase 2: 写入 PGvector) ===
            // TODO: vectorStore.add(chunks);

            // === Complete ===
            doc.setChunkCount(chunks.size());
            doc.setStatus("COMPLETED");
            doc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(doc);

            log.info("ETL pipeline completed for document: id={}, chunks={}", documentId, chunks.size());
            return chunks.size();

        } catch (Exception e) {
            log.error("ETL pipeline failed for document: id={}", documentId, e);
            doc.setStatus("FAILED");
            doc.setErrorMessage(truncate(e.getMessage(), 2000));
            doc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(doc);
            throw new RuntimeException("Document processing failed: " + fileName, e);
        }
    }

    private void updateStatus(Long documentId, String status) {
        RagDocument update = new RagDocument();
        update.setId(documentId);
        update.setStatus(status);
        update.setUpdateTime(LocalDateTime.now());
        ragDocumentMapper.updateById(update);
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen);
    }
}
