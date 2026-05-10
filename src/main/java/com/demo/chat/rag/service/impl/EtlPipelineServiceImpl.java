package com.demo.chat.rag.service.impl;

import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.etl.EtlStatusManager;
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

import java.util.List;

/**
 * ETL Pipeline 编排器
 * <p>
 * 只负责串联 Extract → Transform → Load 三个阶段，并管理文档状态流转。
 * 状态管理委托给 {@link EtlStatusManager}。
 * <p>
 * 注意：此实现保留用于直接调用场景（不经过 EtlDispatchService）。
 * 新代码应优先使用 {@link com.demo.chat.rag.service.EtlDispatchService}。
 */
@Service
public class EtlPipelineServiceImpl implements EtlPipelineService {

    private static final Logger log = LoggerFactory.getLogger(EtlPipelineServiceImpl.class);

    private final Extractor extractor;
    private final Transformer transformer;
    private final Loader loader;
    private final RagDocumentMapper ragDocumentMapper;
    private final EtlStatusManager statusManager;

    public EtlPipelineServiceImpl(Extractor extractor,
                                  Transformer transformer,
                                  Loader loader,
                                  RagDocumentMapper ragDocumentMapper,
                                  EtlStatusManager statusManager) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.loader = loader;
        this.ragDocumentMapper = ragDocumentMapper;
        this.statusManager = statusManager;
    }

    @Override
    public int execute(Long documentId, String bucket, String objectKey, String fileName, String mimeType) {
        throw new UnsupportedOperationException("Use EtlDispatchService.executeSingle() instead");
    }

    /**
     * 带用户隔离的 ETL 执行（由 EtlDispatchService 委托调用）
     */
    public int executeWithUserId(Long documentId, String bucket, String objectKey, String fileName, String mimeType, Long userId) {
        log.info("ETL pipeline started: id={}, file={}", documentId, fileName);

        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException("文档不存在: " + documentId);
        }

        try {
            // === Extract ===
            statusManager.updateStatus(documentId, EtlStatus.PARSING);
            List<Document> rawDocuments = extractor.extract(bucket, objectKey, mimeType);

            // === Transform ===
            statusManager.updateStatus(documentId, EtlStatus.CHUNKING);
            List<Document> chunks = transformer.transform(rawDocuments, fileName);

            String docIdStr = String.valueOf(documentId);
            String userIdStr = String.valueOf(userId);
            for (Document chunk : chunks) {
                chunk.getMetadata().put("documentId", docIdStr);
                chunk.getMetadata().put("userId", userIdStr);
            }

            // === Load ===
            statusManager.updateStatus(documentId, EtlStatus.VECTORIZING);
            loader.load(chunks);

            // === Complete ===
            statusManager.completeDocument(documentId, chunks.size());

            log.info("ETL completed: id={}, chunks={}", documentId, chunks.size());
            return chunks.size();

        } catch (Exception e) {
            statusManager.failDocument(documentId, e);
            throw new BusinessException("文档处理失败: " + fileName, e);
        }
    }
}
