package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.rag.etl.EtlCandidate;
import com.smart.rag.rag.etl.EtlResult;
import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.etl.EtlRouteStrategy;
import com.smart.rag.rag.etl.EtlRouteStrategyFactory;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.event.EtlCompletedEvent;
import com.smart.rag.rag.service.EtlDispatchService;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * ETL 调度服务实现
 * <p>
 * 通过 {@link EtlRouteStrategyFactory} 选择策略并执行。
 * 单文档场景包装为单元素列表走 dispatch，保持行为一致。
 */
@Service
public class EtlDispatchServiceImpl implements EtlDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EtlDispatchServiceImpl.class);

    private static final String ETL_LOCK_PREFIX = "smart-rag:etl:lock:";
    /** 等待获取锁的最大时间 */
    private static final long LOCK_WAIT_SECONDS = 30;

    private final EtlRouteStrategyFactory strategyFactory;
    private final Executor etlIoExecutor;
    private final Loader loader;
    private final ApplicationEventPublisher eventPublisher;
    private final @Nullable RedissonClient redissonClient;

    public EtlDispatchServiceImpl(EtlRouteStrategyFactory strategyFactory,
                                  @Qualifier("etlIoExecutor") Executor etlIoExecutor,
                                  Loader loader,
                                  ApplicationEventPublisher eventPublisher,
                                  @Nullable RedissonClient redissonClient) {
        this.strategyFactory = strategyFactory;
        this.etlIoExecutor = etlIoExecutor;
        this.loader = loader;
        this.eventPublisher = eventPublisher;
        this.redissonClient = redissonClient;
    }

    @Override
    public List<EtlResult> dispatch(List<EtlCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        EtlRouteStrategy strategy = strategyFactory.resolve(candidates);
        log.info("ETL dispatch: {} candidates → strategy={}", candidates.size(), strategy.getClass().getSimpleName());

        if (redissonClient == null) {
            // No Redisson (test profile), execute without lock
            return strategy.execute(candidates);
        }

        // Acquire distributed locks for all candidates
        List<RLock> locks = candidates.stream()
            .map(c -> redissonClient.getLock(ETL_LOCK_PREFIX + c.documentId()))
            .toList();

        try {
            // Try to acquire all locks sequentially.
            // leaseTime=-1 triggers Redisson watchdog auto-renewal (default 30s interval),
            // preventing lock expiry during long-running ETL jobs.
            for (RLock lock : locks) {
                if (!lock.tryLock(LOCK_WAIT_SECONDS, -1, TimeUnit.SECONDS)) {
                    log.warn("ETL lock acquisition failed for document, skipping: {}", lock.getName());
                    // Release any already-acquired locks
                    locks.forEach(l -> { if (l.isHeldByCurrentThread()) l.unlock(); });
                    throw new ServiceException(ServiceErrorCode.ETL_FAILED, "文档正在被其他实例处理，请稍后重试");
                }
            }
            return strategy.execute(candidates);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(ServiceErrorCode.ETL_FAILED, "ETL 处理被中断");
        } finally {
            locks.forEach(l -> { if (l.isHeldByCurrentThread()) l.unlock(); });
        }
    }

    @Override
    public int executeSingle(Long documentId, String bucket, String objectKey, String fileName, String mimeType, long fileSize, Long userId, @Nullable Long teamId) {
        EtlCandidate candidate = new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId, teamId);
        List<EtlResult> results = dispatch(List.of(candidate));

        if (results.isEmpty()) {
            throw new ServiceException(ServiceErrorCode.ETL_NO_RESULT, "ETL 处理无结果: " + fileName);
        }

        EtlResult result = results.getFirst();
        if (EtlStatus.FAILED.equals(result.status())) {
            throw new ServiceException(ServiceErrorCode.ETL_FAILED, "文档处理失败: " + fileName + " - " + result.errorMessage());
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
