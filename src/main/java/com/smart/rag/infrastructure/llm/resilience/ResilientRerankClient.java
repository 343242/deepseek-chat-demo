package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.RerankCapable;
import com.smart.rag.infrastructure.llm.RerankRequest;
import com.smart.rag.infrastructure.llm.RerankResult;

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
                                   RetryPolicy retryPolicy) {
        super(delegate, circuitBreaker, retryPolicy);
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        try {
            return circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    delegate.rerank(request)
                )
            );
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request, int topN) {
        try {
            return circuitBreaker.execute(() ->
                retryPolicy.executeWithBackoff(() ->
                    delegate.rerank(request, topN)
                )
            );
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }
}
