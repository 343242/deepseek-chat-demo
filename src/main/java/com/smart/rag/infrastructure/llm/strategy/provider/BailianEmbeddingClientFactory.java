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
 * 创建 DashScope 原生 API 的 Embedding 客户端（支持 text_type、instruct 高级参数），
 * 而非 OpenAI 兼容的 GenericEmbeddingClient。
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
        // 临时硬编码：百炼 embedding 端点已迁移到 workspace 级 MaaS 域名（与 chat/rerank 的 dashscope.aliyuncs.com 不同）。WorkspaceId 后续改为配置项。
        String embeddingBaseUrl = "https://llm-l3buonxbvhgk4qiy.cn-beijing.maas.aliyuncs.com";
        return new BailianEmbeddingClient(embeddingBaseUrl, endpoint, apiKey, candidate, scopedTasks);
    }
}
