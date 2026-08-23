package com.smart.rag.infrastructure.llm.strategy.provider;

import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.client.bailian.BailianEmbeddingClient;
import com.smart.rag.infrastructure.llm.strategy.ProviderClientFactory;
import org.springframework.stereotype.Component;

/**
 * 百炼 Embedding 客户端工厂
 * <p>
 * 产出 SDK 实现 {@link BailianEmbeddingClient}（DashScope 原生 embedding 路由，
 * text_type/instruct 高级参数支持）。域名来源为 {@code provider.url}（剥离兼容层路径后
 * 追加 {@code /api/v1}），不再硬编码 workspace 域名——dev/stable 均由各自 provider.url
 * 解析（设计 §4.5）。
 */
@Component
public class BailianEmbeddingClientFactory implements ProviderClientFactory {

    private final ScopedTasks scopedTasks;

    public BailianEmbeddingClientFactory(ScopedTasks scopedTasks) {
        this.scopedTasks = scopedTasks;
    }

    @Override
    public String providerId() {
        return "bailian";
    }

    @Override
    public LlmCapability capability() {
        return LlmCapability.EMBEDDING;
    }

    @Override
    public CapabilityClient create(String baseUrl, String endpoint, String apiKey, ModelCandidate candidate) {
        // endpoint 参数仅 Generic 回退路径消费；SDK facade 内置原生路由路径
        return new BailianEmbeddingClient(DashScopeUrls.sdkBaseUrl(baseUrl), apiKey, candidate, scopedTasks);
    }
}
