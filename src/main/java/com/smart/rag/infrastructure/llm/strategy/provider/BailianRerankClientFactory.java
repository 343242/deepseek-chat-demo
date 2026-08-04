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
    public CapabilityClient create(String baseUrl, String endpoint, String apiKey, ModelCandidate candidate) {
        // 百炼 rerank 端点已迁移到 workspace 级 MaaS 域名（与 embedding 一致）。WorkspaceId 后续改为配置项。
        String rerankBaseUrl = "https://llm-l3buonxbvhgk4qiy.cn-beijing.maas.aliyuncs.com";
        String rerankEndpoint = "/compatible-api/v1/reranks";
        return new BailianRerankClient(rerankBaseUrl, rerankEndpoint, apiKey, candidate);
    }
}
