package com.smart.rag.infrastructure.llm.client.generic;

import com.smart.rag.infrastructure.llm.*;
import com.smart.rag.infrastructure.llm.client.AbstractChatClient;
import reactor.core.publisher.Flux;

/**
 * 通用 Chat 客户端（OpenAI 兼容 API）
 * <p>
 * 基于 OpenAI 兼容的 /v1/chat/completions 端点。
 * <p>
 * <b>Phase 3 TODO</b>：实现 HTTP 调用逻辑（WebClient / RestTemplate）。
 */
public class GenericChatClient extends AbstractChatClient {

    protected final String baseUrl;
    protected final String endpoint;
    protected final String apiKey;

    public GenericChatClient(String baseUrl, String endpoint,
                             String apiKey, ModelCandidate candidate) {
        super(candidate, candidate.provider());
        this.baseUrl = baseUrl;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public LlmResponse chat(ChatRequest request) {
        // Phase 3: implement OpenAI-compatible /v1/chat/completions call
        throw new UnsupportedOperationException(
            "GenericChatClient.chat() not yet implemented (Phase 3)");
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        // Phase 3: implement OpenAI-compatible /v1/chat/completions streaming
        throw new UnsupportedOperationException(
            "GenericChatClient.chatStream() not yet implemented (Phase 3)");
    }
}
