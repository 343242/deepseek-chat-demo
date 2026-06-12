package com.smart.rag.infrastructure.llm.provider.generic;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmProvider;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.strategy.CapabilityStrategy;
import com.smart.rag.infrastructure.llm.strategy.CapabilityStrategyRegistry;

/**
 * 通用 OpenAI 兼容供应商
 * <p>
 * 委托 {@link CapabilityStrategy} 创建客户端——策略层先检查 ProviderClientFactory
 * （供应商差异化工厂），再回退到 Generic 客户端（OpenAI 兼容 API）。
 */
public class GenericOpenAiProvider implements LlmProvider {

    private final String providerId;
    private final ProviderConfig providerConfig;
    private final CapabilityStrategyRegistry strategyRegistry;

    public GenericOpenAiProvider(String providerId,
                                 ProviderConfig providerConfig,
                                 CapabilityStrategyRegistry strategyRegistry) {
        this.providerId = providerId;
        this.providerConfig = providerConfig;
        this.strategyRegistry = strategyRegistry;
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public ProviderConfig config() {
        return providerConfig;
    }

    @Override
    public CapabilityClient createClient(ModelCandidate candidate) {
        CapabilityStrategy strategy = strategyRegistry.get(candidate.capability());
        String baseUrl = providerConfig.url();
        String endpoint = strategy.resolveEndpoint(providerConfig);
        String apiKey = providerConfig.apiKey();
        return strategy.createClient(baseUrl, endpoint, apiKey, candidate);
    }
}
