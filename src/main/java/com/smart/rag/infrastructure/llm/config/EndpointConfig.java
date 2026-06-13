package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.llm.LlmCapability;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 供应商端点配置——按能力映射 URL 路径
 * <p>
 * 对应 YAML {@code providers.<id>.endpoints}，例如 {@code { chat: /v1/chat/completions }}。
 * key 为能力名小写（chat / embedding / reranking），value 为路径。
 */
public record EndpointConfig(
    /** 能力 → 路径映射（如 "chat" → "/v1/chat/completions"） */
    Map<String, String> endpoints
) {

    private static final EndpointConfig EMPTY = new EndpointConfig(Map.of());

    public EndpointConfig {
        if (endpoints == null) {
            endpoints = Map.of();
        }
    }

    public static EndpointConfig empty() {
        return EMPTY;
    }

    public static EndpointConfig of(Map<String, String> endpoints) {
        return new EndpointConfig(endpoints);
    }

    /**
     * 按能力获取端点路径，null-safe
     *
     * @return 端点路径，未配置时返回 null
     */
    @Nullable
    public String get(LlmCapability capability) {
        return endpoints.get(capability.name().toLowerCase());
    }
}
