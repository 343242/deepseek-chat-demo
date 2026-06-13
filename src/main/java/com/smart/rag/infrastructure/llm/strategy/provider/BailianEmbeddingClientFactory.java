package com.smart.rag.infrastructure.llm.strategy.provider;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.EmbeddingCapable;
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
        return new BailianEmbeddingClient(baseUrl, endpoint, apiKey, candidate);
    }
}
