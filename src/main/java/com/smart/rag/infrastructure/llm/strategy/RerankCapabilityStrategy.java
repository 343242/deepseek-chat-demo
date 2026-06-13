package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.RerankCapable;
import com.smart.rag.infrastructure.llm.client.generic.GenericRerankClient;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.ProbeHandler;
import com.smart.rag.infrastructure.llm.resilience.RetryPolicy;
import com.smart.rag.infrastructure.llm.resilience.ResilientRerankClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RERANKING 策略 — 含 ProviderClientFactory 自动发现
 * <p>
 * 供应商差异化扩展：当 {@link ProviderClientFactory} 注册了
 * {@code providerId:RERANKING} 工厂时，委托工厂创建原生 API 客户端；
 * 否则使用通用 {@link GenericRerankClient}（Cohere 风格 API）。
 */
@Component
public class RerankCapabilityStrategy extends AbstractProviderFactoryAwareStrategy {

    public RerankCapabilityStrategy(List<ProviderClientFactory> factories) {
        super(factories);
    }

    @Override public LlmCapability capability() { return LlmCapability.RERANKING; }

    @Override
    protected CapabilityClient createGenericClient(String baseUrl, String endpoint,
                                                    String apiKey, ModelCandidate candidate) {
        return new GenericRerankClient(baseUrl, endpoint, apiKey, candidate);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe,
                                                @Nullable LlmMetrics metrics) {
        if (!(raw instanceof RerankCapable rerankCapable)) {
            throw new IllegalStateException(
                "RERANKING client '" + raw.candidateId() + "' does not implement RerankCapable");
        }
        return new ResilientRerankClient(rerankCapable, cb, retry, metrics);
    }
}
