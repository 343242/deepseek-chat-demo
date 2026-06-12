package com.smart.rag.infrastructure.llm.client.generic;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractEmbeddingClient;

/**
 * 通用 Embedding 客户端（OpenAI 兼容 API）
 * <p>
 * 基于 OpenAI 兼容的 /v1/embeddings 端点。
 * <p>
 * <b>Phase 3 TODO</b>：实现 HTTP 调用逻辑。
 */
public class GenericEmbeddingClient extends AbstractEmbeddingClient {

    protected final String baseUrl;
    protected final String endpoint;
    protected final String apiKey;

    public GenericEmbeddingClient(String baseUrl, String endpoint,
                                  String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.baseUrl = baseUrl;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public float[] embed(String text, EmbeddingType type) {
        // Phase 3: implement OpenAI-compatible /v1/embeddings call
        throw new UnsupportedOperationException(
            "GenericEmbeddingClient.embed() not yet implemented (Phase 3)");
    }

    @Override
    public int dimension() {
        return candidate.dimension();
    }
}
