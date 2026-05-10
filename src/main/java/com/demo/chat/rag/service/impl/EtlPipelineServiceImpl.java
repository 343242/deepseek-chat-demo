package com.demo.chat.rag.service.impl;

import com.demo.chat.rag.etl.Extractor;
import com.demo.chat.rag.etl.Loader;
import com.demo.chat.rag.etl.Transformer;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import com.demo.chat.rag.service.EtlPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ETL Pipeline 编排器
 * <p>
 * 只负责串联 Extract → Transform → Load 三个阶段，并管理文档状态流转。
 * 各阶段的具体逻辑封装在独立的 {@link Extractor}、{@link Transformer}、{@link Loader} 实现中，
 * 本类不包含任何解析/分块/存储的细节代码。
 * </p>
 */
@Service
public class EtlPipelineServiceImpl implements EtlPipelineService {

    private static final Logger log = LoggerFactory.getLogger(EtlPipelineServiceImpl.class);

    private final Extractor extractor;
    private final Transformer transformer;
    private final Loader loader;
    private final RagDocumentMapper ragDocumentMapper;

    public EtlPipelineServiceImpl(Extractor extractor,
                                  Transformer transformer,
                                  Loader loader,
                                  RagDocumentMapper ragDocumentMapper) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.loader = loader;
        this.ragDocumentMapper = ragDocumentMapper;
    }

    @Override
    @Transactional
    public int execute(Long documentId, String bucket, String objectKey, String fileName, String mimeType) {
        log.info("ETL pipeline started: id={}, file={}", documentId, fileName);

        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new IllegalArgumentException("Document not found: " + documentId);
        }

        try {
            // === Extract ===
            updateStatus(documentId, "PARSING");
            List<Document> rawDocuments = extractor.extract(bucket, objectKey, mimeType);

            // === Transform ===
            updateStatus(documentId, "CHUNKING");
            List<Document> chunks = transformer.transform(rawDocuments, fileName);

            // 为每个 chunk 注入 documentId，用于后续按文档删除向量数据
            String docIdStr = String.valueOf(documentId);
            for (Document chunk : chunks) {
                chunk.getMetadata().put("documentId", docIdStr);
            }

            // === Load ===
            updateStatus(documentId, "VECTORIZING");
            loader.load(chunks);

            // === Complete ===
            completeDocument(documentId, chunks.size());

            log.info("ETL completed: id={}, chunks={}", documentId, chunks.size());
            return chunks.size();

        } catch (Exception e) {
            failDocument(documentId, e);
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

    private void completeDocument(Long documentId, int chunkCount) {
        RagDocument doc = new RagDocument();
        doc.setId(documentId);
        doc.setStatus("COMPLETED");
        doc.setChunkCount(chunkCount);
        doc.setUpdateTime(LocalDateTime.now());
        ragDocumentMapper.updateById(doc);
    }

    private void failDocument(Long documentId, Exception e) {
        log.error("ETL failed for document: id={}", documentId, e);
        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc != null) {
            doc.setStatus("FAILED");
            doc.setErrorMessage(truncate(e.getMessage(), 2000));
            doc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(doc);
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen);
    }
}
