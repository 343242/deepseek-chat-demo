package com.demo.chat.rag.service.impl;

import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import com.demo.chat.rag.etl.EtlCandidate;
import com.demo.chat.rag.etl.EtlResult;
import com.demo.chat.rag.etl.Loader;
import com.demo.chat.rag.etl.EtlRouteStrategy;
import com.demo.chat.rag.etl.EtlRouteStrategyFactory;
import com.demo.chat.rag.etl.EtlStatus;
import com.demo.chat.rag.service.EtlDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public EtlDispatchServiceImpl(EtlRouteStrategyFactory strategyFactory,
                                  @Qualifier("etlIoExecutor") ThreadPoolTaskExecutor etlIoExecutor,
                                  Loader loader) {
        this.strategyFactory = strategyFactory;
        this.etlIoExecutor = etlIoExecutor;
        this.loader = loader;
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
    public int executeSingle(Long documentId, String bucket, String objectKey, String fileName, String mimeType, long fileSize, Long userId) {
        EtlCandidate candidate = new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId);
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
    public void dispatchAsync(Long documentId, String bucket, String objectKey, String fileName, String mimeType, long fileSize, Long userId) {
        EtlCandidate candidate = new EtlCandidate(documentId, bucket, objectKey, fileName, mimeType, fileSize, userId);
        log.info("ETL dispatchAsync: documentId={}, file={}, userId={}", documentId, fileName, userId);

        etlIoExecutor.execute(() -> {
            try {
                dispatch(List.of(candidate));
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
