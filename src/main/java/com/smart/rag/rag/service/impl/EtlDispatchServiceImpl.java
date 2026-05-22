package com.smart.rag.rag.service.impl;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.rag.etl.EtlCandidate;
import com.smart.rag.rag.etl.EtlResult;
import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.etl.EtlRouteStrategy;
import com.smart.rag.rag.etl.EtlRouteStrategyFactory;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.event.EtlCompletedEvent;
import com.smart.rag.rag.service.EtlDispatchService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

/**
 * ETL 调度服务实现
 * <p>
 * 通过 {@link EtlRouteStrategyFactory} 选择策略并执行。
 * 单文档场景包装为单元素列表走 dispatch，保持行为一致。
 */
@Service
public class EtlDispatchServiceImpl implements EtlDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EtlDispatchServiceImpl.class);

    private final EtlRouteStrategyFactory strategyFactory;
    private final ThreadPoolTaskExecutor etlIoExecutor;
    private final Loader loader;
    private final ApplicationEventPublisher eventPublisher;

    public EtlDispatchServiceImpl(EtlRouteStrategyFactory strategyFactory,
                                  @Qualifier("etlIoExecutor") ThreadPoolTaskExecutor etlIoExecutor,
                                  Loader loader,
                                  ApplicationEventPublisher eventPublisher) {
        this.strategyFactory = strategyFactory;
        this.etlIoExecutor = etlIoExecutor;
        this.loader = loader;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<EtlResult> dispatch(List<EtlCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        EtlRouteStrategy strategy = strategyFactory.resolve(candidates);
        log.info("ETL dispatch: {} candidates → strategy={}", candidates.size(), strategy.getClass().getSimpleName());

        return strategy.execute(candidates);
    }

    @Override
    public int executeSingle(Long documentId, String bucket, String objectKey, String fileName, String mimeType, long fileSize, Long userId, @Nullable Long teamId) {
        EtlCandidate candidate = new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId, teamId);
        List<EtlResult> results = dispatch(List.of(candidate));

        if (results.isEmpty()) {
            throw new BusinessException(ErrorCode.ETL_NO_RESULT, "ETL 处理无结果: " + fileName);
        }

        EtlResult result = results.getFirst();
        if (EtlStatus.FAILED.equals(result.status())) {
            throw new BusinessException(ErrorCode.ETL_FAILED, "文档处理失败: " + fileName + " - " + result.errorMessage());
        }

        return result.chunkCount();
    }

    @Override
    public void dispatchAsync(Long documentId, String bucket, String objectKey, String fileName, String mimeType, long fileSize, Long userId, @Nullable Long teamId) {
        EtlCandidate candidate = new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId, teamId);
        log.info("ETL dispatchAsync: documentId={}, file={}, userId={}, teamId={}", documentId, fileName, userId, teamId);

        etlIoExecutor.execute(() -> {
            try {
                List<EtlResult> results = dispatch(List.of(candidate));
                if (!results.isEmpty() && EtlStatus.COMPLETED.equals(results.getFirst().status())) {
                    eventPublisher.publishEvent(new EtlCompletedEvent(candidate.documentId(), candidate.userId(), candidate.teamId()));
                }
            } catch (Exception e) {
                log.error("ETL dispatchAsync failed: documentId={}, file={}", documentId, fileName, e);
            }
        });
    }

    @Override
    public void deleteVectors(Long documentId) {
        loader.deleteByDocumentId(documentId);
        log.info("Vectors deleted for documentId={}", documentId);
    }
}
