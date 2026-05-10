package com.demo.chat.rag.service.impl;

import com.demo.chat.exception.BusinessException;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ETL Pipeline 编排器
 * <p>
 * 只负责串联 Extract → Transform → Load 三个阶段，并管理文档状态流转。
 * 各阶段的具体逻辑封装在独立的 {@link Extractor}、{@link Transformer}、{@link Loader} 实现中，
 * 本类不包含任何解析/分块/存储的细节代码。
 * </p>
 *
 * <p>事务策略：</p>
 * <ul>
 *   <li>不使用 @Transactional，因为 ETL 包含外部 IO（MinIO 读取、向量库写入）</li>
 *   <li>使用 {@link TransactionTemplate} 对状态更新做独立事务提交</li>
 *   <li>确保 FAILED 状态不随异常回滚而丢失</li>
 * </ul>
 */
@Service
public class EtlPipelineServiceImpl implements EtlPipelineService {

    private static final Logger log = LoggerFactory.getLogger(EtlPipelineServiceImpl.class);

    private final Extractor extractor;
    private final Transformer transformer;
    private final Loader loader;
    private final RagDocumentMapper ragDocumentMapper;
    private final TransactionTemplate transactionTemplate;

    public EtlPipelineServiceImpl(Extractor extractor,
                                  Transformer transformer,
                                  Loader loader,
                                  RagDocumentMapper ragDocumentMapper,
                                  TransactionTemplate transactionTemplate) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.loader = loader;
        this.ragDocumentMapper = ragDocumentMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int execute(Long documentId, String bucket, String objectKey, String fileName, String mimeType) {
        log.info("ETL pipeline started: id={}, file={}", documentId, fileName);

        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException("文档不存在: " + documentId);
        }

        try {
            // === Extract ===
            updateStatusInTransaction(documentId, "PARSING");
            List<Document> rawDocuments = extractor.extract(bucket, objectKey, mimeType);

            // === Transform ===
            updateStatusInTransaction(documentId, "CHUNKING");
            List<Document> chunks = transformer.transform(rawDocuments, fileName);

            // 为每个 chunk 注入 documentId，用于后续按文档删除向量数据
            String docIdStr = String.valueOf(documentId);
            for (Document chunk : chunks) {
                chunk.getMetadata().put("documentId", docIdStr);
            }

            // === Load ===
            updateStatusInTransaction(documentId, "VECTORIZING");
            loader.load(chunks);

            // === Complete ===
            completeDocumentInTransaction(documentId, chunks.size());

            log.info("ETL completed: id={}, chunks={}", documentId, chunks.size());
            return chunks.size();

        } catch (Exception e) {
            failDocumentInTransaction(documentId, e);
            throw new BusinessException("文档处理失败: " + fileName, e);
        }
    }

    /**
     * 在独立事务中更新文档状态，确保状态变更不受外部 IO 异常回滚影响
     */
    private void updateStatusInTransaction(Long documentId, String status) {
        transactionTemplate.executeWithoutResult(ts -> {
            RagDocument update = new RagDocument();
            update.setId(documentId);
            update.setStatus(status);
            update.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(update);
        });
    }

    private void completeDocumentInTransaction(Long documentId, int chunkCount) {
        transactionTemplate.executeWithoutResult(ts -> {
            RagDocument doc = new RagDocument();
            doc.setId(documentId);
            doc.setStatus("COMPLETED");
            doc.setChunkCount(chunkCount);
            doc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(doc);
        });
    }

    private void failDocumentInTransaction(Long documentId, Exception e) {
        log.error("ETL failed for document: id={}", documentId, e);
        try {
            transactionTemplate.executeWithoutResult(ts -> {
                RagDocument doc = ragDocumentMapper.selectById(documentId);
                if (doc != null) {
                    doc.setStatus("FAILED");
                    doc.setErrorMessage(truncate(e.getMessage(), 2000));
                    doc.setUpdateTime(LocalDateTime.now());
                    ragDocumentMapper.updateById(doc);
                }
            });
        } catch (Exception txEx) {
            log.error("Failed to persist FAILED status for document: id={}", documentId, txEx);
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen);
    }
}
