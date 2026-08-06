package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.errorcode.ServiceErrorCode;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.rag.etl.EtlCandidate;
import com.smart.rag.rag.etl.EtlDocumentConsumer;
import com.smart.rag.rag.etl.EtlResult;
import com.smart.rag.rag.etl.EtlRouteStrategy;
import com.smart.rag.rag.etl.EtlRouteStrategyFactory;
import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.service.EtlDispatchService;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
    private final Loader loader;
    private final @Nullable RedissonClient redissonClient;
    private final MessageBus messageBus;
    private final @Nullable MeterRegistry meterRegistry;

    public EtlDispatchServiceImpl(EtlRouteStrategyFactory strategyFactory,
                                  Loader loader,
                                  @Nullable RedissonClient redissonClient,
                                  MessageBus messageBus,
                                  @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
        this.strategyFactory = strategyFactory;
        this.loader = loader;
        this.redissonClient = redissonClient;
        this.messageBus = messageBus;
        this.meterRegistry = meterRegistry;
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
            // R1-M3: 该锁为 best-effort 互斥——watchdog 续期受 GC/网络抖动影响，极端情况下仍
            // 可能提前释放导致并发 ETL；真正的正确性边界是向量库写入的幂等性（按 documentId
            // 去重/覆盖），锁仅用于减少重复计算，不作为唯一正确性保证。
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

        // 单一投递路径：outbox（child 2）→ relay → RedisStream → consumer，保留 FIFO/重试/DLQ 语义。
        // send() 仅 outbox INSERT 失败（DB 硬故障）才抛——catch 记 rag.etl.publish_failed 告警计数；
        // 线程池兜底（dispatchViaThreadPool）已随 outbox 删除（R2：单一投递路径，不与正常 ETL 抢资源）。
        try {
            String dedupKey = String.valueOf(documentId);
            messageBus.send(new MessageEnvelope<>(null, EtlDocumentConsumer.TOPIC, null, candidate,
                dedupKey, dedupKey, Map.of(), System.currentTimeMillis()));
        } catch (MessagingException e) {
            log.error("Outbox persist failed for ETL dispatch (message lost): documentId={}, file={}",
                documentId, fileName, e);
            if (meterRegistry != null) {
                meterRegistry.counter("rag.etl.publish_failed").increment();
            }
        }
    }

    @Override
    public void deleteVectors(Long documentId) {
        loader.deleteByDocumentId(documentId);
        log.info("Vectors deleted for documentId={}", documentId);
    }
}
