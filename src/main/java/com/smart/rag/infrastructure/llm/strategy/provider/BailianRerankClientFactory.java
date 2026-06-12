package com.smart.rag.infrastructure.llm.strategy.provider;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.client.bailian.BailianRerankClient;
import com.smart.rag.infrastructure.llm.strategy.ProviderClientFactory;
import org.springframework.stereotype.Component;

/**
 * 百炼 Rerank 客户端工厂
 * <p>
 * 创建百炼原生 Rerank 客户端，使用 DashScope OpenAI 兼容端点。
 */
@Component
public class BailianRerankClientFactory implements ProviderClientFactory {

    @Override
    public String providerId() {
        return "bailian";
    }

    @Override
    public LlmCapability capability() {
        return LlmCapability.RERANKING;
    }

    @Override
    public CapabilityClient create(String apiKey, ModelCandidate candidate) {
        return new BailianRerankClient("https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey, candidate);
    }
}
