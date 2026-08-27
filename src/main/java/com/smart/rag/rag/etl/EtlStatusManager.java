package com.smart.rag.rag.etl;

import com.smart.rag.rag.entity.RagDocument;
import com.smart.rag.rag.event.DocumentStatusChangedEvent;
import com.smart.rag.rag.event.EtlFailedEvent;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

/**
 * ETL 文档状态管理器
 * <p>
 * 封装文档状态的更新逻辑，使用 TransactionTemplate 独立事务。
 * 被 {@link StandardStrategy} 和 {@link FastTrackStrategy} 共享，
 * 消除重复的状态管理代码。
 * <p>
 * 事件发布是<b>事务感知</b>的（design §6.3 H2）：同步器激活时（如并入图片 manifest
 * 短事务的场景）afterCommit 发布，否则立即发布——事务回滚时 UI 不会收到 COMPLETED
 * 假状态。无事务的既有调用路径行为不变。
 */
@Component
public class EtlStatusManager {

    private static final Logger log = LoggerFactory.getLogger(EtlStatusManager.class);

    /** 错误信息截断上限（UI/日志体积控制，见 truncate 注释） */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final RagDocumentMapper ragDocumentMapper;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public EtlStatusManager(RagDocumentMapper ragDocumentMapper,
                            TransactionTemplate transactionTemplate,
                            ApplicationEventPublisher eventPublisher) {
        this.ragDocumentMapper = ragDocumentMapper;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 更新文档状态（独立事务）
     */
    public void updateStatus(Long documentId, EtlStatus status) {
        transactionTemplate.executeWithoutResult(ts -> {
            RagDocument update = new RagDocument();
            update.setId(documentId);
            update.setStatus(status);
            update.setUpdateTime(OffsetDateTime.now());
            ragDocumentMapper.updateById(update);
        });
        publishStatusEvent(documentId, status);
    }

    /**
     * 标记文档完成（独立事务；被短事务包裹时事务已激活——状态更新并入外层，
     * 事件 afterCommit 发布）
     */
    public void completeDocument(Long documentId, int chunkCount) {
        transactionTemplate.executeWithoutResult(ts -> {
            RagDocument doc = new RagDocument();
            doc.setId(documentId);
            doc.setStatus(EtlStatus.COMPLETED);
            doc.setChunkCount(chunkCount);
            doc.setUpdateTime(OffsetDateTime.now());
            ragDocumentMapper.updateById(doc);
        });
        publishStatusEvent(documentId, EtlStatus.COMPLETED);
    }

    /**
     * 更新分块数量（独立事务，异步向量化完成后调用）
     */
    public void updateChunkCount(Long documentId, int chunkCount) {
        transactionTemplate.executeWithoutResult(ts -> {
            RagDocument doc = new RagDocument();
            doc.setId(documentId);
            doc.setChunkCount(chunkCount);
            doc.setUpdateTime(OffsetDateTime.now());
            ragDocumentMapper.updateById(doc);
        });
    }

    /**
     * 标记文档失败（独立事务）
     */
    public void failDocument(Long documentId, Exception e) {
        log.error("ETL failed for document: id={}", documentId, e);
        try {
            String message = truncate(e.getMessage(), MAX_ERROR_MESSAGE_LENGTH);
            transactionTemplate.executeWithoutResult(ts -> {
                RagDocument doc = ragDocumentMapper.selectById(documentId);
                if (doc != null) {
                    doc.setStatus(EtlStatus.FAILED);
                    doc.setErrorMessage(message);
                    doc.setUpdateTime(OffsetDateTime.now());
                    ragDocumentMapper.updateById(doc);
                }
            });
            // 事务已提交、DB 状态可见 → 发布失败事件，供下游（pendingSupersede 加速层等）清理
            publishAfterCommitOrNow(() -> {
                eventPublisher.publishEvent(new EtlFailedEvent(documentId, message));
                doPublishStatusEvent(documentId, EtlStatus.FAILED);
            });
        } catch (Exception txEx) {
            log.error("Failed to persist FAILED status for document: id={}", documentId, txEx);
            // 事务失败 → 不发事件，保持「DB=FAILED ⟺ 发 EtlFailedEvent」一致
        }
    }

    /**
     * 标记向量化失败（BM25 仍可用）
     */
    public void markVectorFailed(Long documentId, Throwable ex) {
        log.error("Vectorization failed (BM25 still available): id={}", documentId, ex);
        try {
            transactionTemplate.executeWithoutResult(ts -> {
                RagDocument doc = ragDocumentMapper.selectById(documentId);
                if (doc != null) {
                    doc.setStatus(EtlStatus.VECTOR_FAILED);
                    doc.setErrorMessage(truncate("Async vectorize failed: " + ex.getMessage(), MAX_ERROR_MESSAGE_LENGTH));
                    doc.setUpdateTime(OffsetDateTime.now());
                    ragDocumentMapper.updateById(doc);
                }
            });
            publishStatusEvent(documentId, EtlStatus.VECTOR_FAILED);
        } catch (Exception txEx) {
            log.error("Failed to persist VECTOR_FAILED status: id={}", documentId, txEx);
        }
    }

    /**
     * 发布状态变更事件（事务感知）。事务同步器激活（调用方持有事务，REQUIRED 并入）时
     * afterCommit 发布；否则立即发布（H2）。
     */
    private void publishStatusEvent(Long documentId, EtlStatus status) {
        publishAfterCommitOrNow(() -> doPublishStatusEvent(documentId, status));
    }

    private void doPublishStatusEvent(Long documentId, EtlStatus status) {
        try {
            RagDocument doc = ragDocumentMapper.selectById(documentId);
            if (doc != null) {
                eventPublisher.publishEvent(new DocumentStatusChangedEvent(
                        documentId, doc.getUserId(), doc.getTeamId(), status));
            }
        } catch (Exception e) {
            log.warn("Failed to publish status event: doc={}, status={}", documentId, status, e);
        }
    }

    /**
     * 事务感知发布 helper（H2）：同步器激活 → afterCommit；否则立即执行。
     */
    private static void publishAfterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 截断错误信息。R1-M9: {@code error_message} 列为 PostgreSQL TEXT
     * （{@code V1__init_schema.sql}，无长度上限），故 {@code maxLen=2000} 并非列宽约束，
     * 而是有意的 UI / 日志上限——避免异常堆栈膨胀撑爆响应体与日志，同时保留足够诊断信息。
     */
    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen);
    }
}
