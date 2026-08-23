package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 抽象基类 — 消除 ChatCapabilityStrategy / EmbeddingCapabilityStrategy /
 * RerankCapabilityStrategy 中 providerFactories 收集与 createClient 模板的 DRY 违反。
 * <p>
 * 子类只需：
 * <ol>
 *   <li>提供 {@link #capability()} 返回值（来自 {@link CapabilityStrategy}）</li>
 *   <li>实现 {@link #createGenericClient(String, String, String, ModelCandidate)} 返回 fallback 客户端</li>
 * </ol>
 */
public abstract class AbstractProviderFactoryAwareStrategy implements CapabilityStrategy {

    private final Map<String, ProviderClientFactory> providerFactories;

    protected AbstractProviderFactoryAwareStrategy(List<ProviderClientFactory> factories) {
        this.providerFactories = factories.stream()
            .filter(f -> f.capability() == capability())
            .collect(Collectors.toUnmodifiableMap(
                f -> f.providerId() + ":" + f.capability(),
                Function.identity(),
                (a, b) -> { throw new IllegalStateException(
                    "Duplicate ProviderClientFactory for " + a.capability()
                    + " provider '" + a.providerId()
                    + "': " + a.getClass().getName() + " vs " + b.getClass().getName()); }));
    }

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
        return createGenericClient(baseUrl, endpoint, apiKey, candidate);
    }

    /**
     * 当无 ProviderClientFactory 注册到该供应商时，返回通用 fallback 客户端
     * （如 {@code GenericEmbeddingClient}、{@code GenericRerankClient}）。
     */
    protected abstract CapabilityClient createGenericClient(String baseUrl, String endpoint,
                                                            String apiKey, ModelCandidate candidate);
}
