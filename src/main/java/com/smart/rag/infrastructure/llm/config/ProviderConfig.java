package com.smart.rag.infrastructure.llm.config;

import java.util.Map;

/**
 * 供应商连接配置
 * <p>
 * 对应 YAML 中 {@code providers.<id>} 下的连接信息。
 */
public record ProviderConfig(
    /** 基础 URL（如 https://dashscope.aliyuncs.com/compatible-mode） */
    String url,

    /** API Key */
    String apiKey,

    /** 能力端点映射（如 chat: /v1/chat/completions, embedding: /v1/embeddings） */
    Map<String, String> endpoints
) {
    public static ProviderConfig of(String url, String apiKey) {
        return new ProviderConfig(url, apiKey, Map.of());
    }

    public static ProviderConfig of(String url, String apiKey, Map<String, String> endpoints) {
        return new ProviderConfig(url, apiKey, endpoints);
    }
}
