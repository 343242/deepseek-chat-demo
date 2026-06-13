package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.client.generic.GenericEmbeddingClient;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.ProbeHandler;
import com.smart.rag.infrastructure.llm.resilience.RetryPolicy;
import com.smart.rag.infrastructure.llm.resilience.ResilientEmbeddingClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * EMBEDDING 策略 — 含 ProviderClientFactory 自动发现
 * <p>
 * 供应商差异化扩展：当 {@link ProviderClientFactory} 注册了
 * {@code providerId:EMBEDDING} 工厂时，委托工厂创建原生 API 客户端；
 * 否则使用通用 {@link GenericEmbeddingClient}（OpenAI 兼容 API）。
 */
@Component
public class EmbeddingCapabilityStrategy implements CapabilityStrategy {

    private final Map<String, ProviderClientFactory> providerFactories;

    public EmbeddingCapabilityStrategy(List<ProviderClientFactory> factories) {
        this.providerFactories = factories.stream()
            .filter(f -> f.capability() == LlmCapability.EMBEDDING)
            .collect(Collectors.toUnmodifiableMap(
                f -> f.providerId() + ":" + f.capability(),
                Function.identity(),
                (a, b) -> { throw new IllegalStateException(
                    "Duplicate ProviderClientFactory for EMBEDDING provider '" + a.providerId()
                    + "': " + a.getClass().getName() + " vs " + b.getClass().getName()); }));
    }

    @Override public LlmCapability capability() { return LlmCapability.EMBEDDING; }

    @Override
    public String resolveEndpoint(ProviderConfig config) {
        return config.getEndpoint(capability());
    }

    @Override
    public CapabilityClient createClient(String baseUrl, String endpoint,
                                          String apiKey, ModelCandidate candidate) {
        ProviderClientFactory factory = providerFactories.get(
            candidate.provider() + ":" + capability());
        if (factory != null) {
            return factory.create(baseUrl, endpoint, apiKey, candidate);
        }
        return new GenericEmbeddingClient(baseUrl, endpoint, apiKey, candidate);
    }

    @Override
    public CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                CircuitBreaker cb,
                                                RetryPolicy retry,
                                                @Nullable ProbeHandler probe,
                                                @Nullable LlmMetrics metrics) {
        if (!(raw instanceof EmbeddingCapable embeddingCapable)) {
            throw new IllegalStateException(
                "EMBEDDING client '" + raw.candidateId() + "' does not implement EmbeddingCapable");
        }
        return new ResilientEmbeddingClient(embeddingCapable, cb, retry, metrics);
    }
}
