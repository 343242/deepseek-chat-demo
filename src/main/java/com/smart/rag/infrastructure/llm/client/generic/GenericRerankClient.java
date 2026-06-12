package com.smart.rag.infrastructure.llm.client.generic;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractRerankClient;

import java.util.List;

/**
 * 通用 Rerank 客户端（OpenAI 兼容 API）
 * <p>
 * 基于 Cohere 兼容的 /v1/rerank 端点。
 * <p>
 * <b>Phase 3 TODO</b>：实现 HTTP 调用逻辑。
 */
public class GenericRerankClient extends AbstractRerankClient {

    protected final String baseUrl;
    protected final String endpoint;
    protected final String apiKey;

    public GenericRerankClient(String baseUrl, String endpoint,
                               String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.baseUrl = baseUrl;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public List<RerankResult> rerank(RerankRequest request) {
        // Phase 3: implement rerank API call
        throw new UnsupportedOperationException(
            "GenericRerankClient.rerank() not yet implemented (Phase 3)");
    }
}
