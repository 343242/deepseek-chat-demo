package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.RerankCapable;
import com.smart.rag.infrastructure.llm.RerankRequest;
import com.smart.rag.infrastructure.llm.RerankResult;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * ResilientRerankClient — Rerank 能力的弹性装饰器
 *
 * 策略矩阵：
 * ┌──────────────┬──────────────┬──────────────┬──────────────┐
 * │   操作类型     │ 重试策略      │ 熔断保护      │ 首包探测      │
 * ├──────────────┼──────────────┼──────────────┼──────────────┤
 * │ rerank       │ 指数退避重试   │ ✓            │ ✗            │
 * └──────────────┴──────────────┴──────────────┴──────────────┘
 */
public class ResilientRerankClient extends AbstractResilientClient<RerankCapable> implements RerankCapable {

    public ResilientRerankClient(RerankCapable delegate,
                                   CircuitBreaker circuitBreaker,
                                   RetryPolicy retryPolicy,
                                   @Nullable LlmMetrics metrics) {
        super(delegate, circuitBreaker, retryPolicy, metrics);
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        return executeResilient(
            () -> delegate.rerank(request),
            (cid, start) -> metrics.recordRerankLatency(cid, start, "success"),
            (cid, start) -> metrics.recordRerankLatency(cid, start, "error")
        );
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request, int topN) {
        return executeResilient(
            () -> delegate.rerank(request, topN),
            (cid, start) -> metrics.recordRerankLatency(cid, start, "success"),
            (cid, start) -> metrics.recordRerankLatency(cid, start, "error")
        );
    }
}
