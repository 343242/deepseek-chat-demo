package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.etl.EtlStatusManager;
import com.smart.rag.rag.etl.Extractor;
import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.etl.Transformer;
import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.service.EtlDispatchService;
import com.smart.rag.rag.service.EtlPipelineService;
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
 * 新代码应优先使用 {@link EtlDispatchService}。
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
        // 委托给带 userId 的方法，兼容旧接口调用方
        // 注意：缺少 userId 的调用无法进行用户隔离，不建议使用
        throw new UnsupportedOperationException("Use EtlDispatchService.dispatchAsync() instead");
    }

    /**
     * 带用户隔离的 ETL 执行（由 EtlDispatchService 委托调用）
     */
    public int executeWithUserId(Long documentId, String bucket, String objectKey, String fileName, String mimeType, Long userId) {
        log.info("ETL pipeline started: id={}, file={}", documentId, fileName);

        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new ServiceException(ServiceErrorCode.DOCUMENT_NOT_FOUND, "文档不存在: " + documentId);
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
            String teamIdStr = doc.getTeamId() != null ? String.valueOf(doc.getTeamId()) : null;
            String fileNameStr = (fileName != null && !fileName.isBlank()) ? fileName : docIdStr;
            for (Document chunk : chunks) {
                chunk.getMetadata().put("documentId", docIdStr);
                chunk.getMetadata().put("userId", userIdStr);
                chunk.getMetadata().put("fileName", fileNameStr);
                if (teamIdStr != null) {
                    chunk.getMetadata().put("teamId", teamIdStr);
                }
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
            throw new ServiceException(ServiceErrorCode.ETL_FAILED, "文档处理失败: " + fileName);
        }
    }
}
