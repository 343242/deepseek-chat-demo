package com.smart.rag.infrastructure.llm.strategy.provider;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.client.HttpClientFactory;
import com.smart.rag.infrastructure.llm.client.bailian.BailianRerankClient;
import com.smart.rag.infrastructure.llm.strategy.ProviderClientFactory;
import org.springframework.stereotype.Component;

/**
 * 百炼 Rerank 客户端工厂
 * <p>
 * 产出保留的手写 {@link BailianRerankClient}（qwen3-rerank 官方推荐路径即 OpenAI 兼容端点
 * {@code /compatible-api/v1/reranks}，设计决策 5 不迁移 SDK）。域名来源为
 * {@code provider.url} 的域名部分（剥离兼容层路径——rerank 兼容端点为绝对路径，直接拼接
 * provider.url 会产生 {@code /compatible-mode/v1/compatible-api/...} 双路径），endpoint 取
 * {@code endpoints.rerank} 声明，未声明时回退 {@code /compatible-api/v1/reranks} 默认值。
 */
@Component
public class BailianRerankClientFactory implements ProviderClientFactory {

    private final HttpClientFactory httpClientFactory;

    public BailianRerankClientFactory(HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;
    }

    /** qwen3-rerank 专用兼容端点（默认值；profile 可经 endpoints.rerank 覆盖） */
    static final String DEFAULT_RERANK_ENDPOINT = "/compatible-api/v1/reranks";

    @Override
    public String providerId() {
        return "bailian";
    }

    @Override
    public LlmCapability capability() {
        return LlmCapability.RERANKING;
    }

    @Override
    public CapabilityClient create(String baseUrl, String endpoint, String apiKey, ModelCandidate candidate) {
        String domain = DashScopeUrls.domainBase(baseUrl);
        String rerankEndpoint = endpoint != null && !endpoint.isBlank() ? endpoint : DEFAULT_RERANK_ENDPOINT;
        return new BailianRerankClient(httpClientFactory, domain, rerankEndpoint, apiKey, candidate);
    }
}
