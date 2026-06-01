package com.smart.rag.infrastructure.fallback;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 聊天兜底策略配置
 * <p>
 * 支持模型级粒度的降级链配置。当主模型调用失败时，
 * 按配置的降级链依次尝试备选模型，直到成功或链耗尽。
 * <p>
 * 配置示例：
 * <pre>
 * app:
 *   chat:
 *     fallback:
 *       enabled: true
 *       max-retries: 3
 *       default-chain:
 *         - deepseek/deepseek-v4-flash
 *         - zhipu/glm-4.7-flash
 *       chains:
 *         deepseek/deepseek-v4-flash:
 *           - zhipu/glm-4.7-flash
 *           - minimax/MiniMax-M2.1
 * </pre>
 *
 * @param enabled       是否启用兜底策略，默认 true
 * @param maxRetries    同模型最大重试次数（流式专用，含首次请求），默认 3
 * @param defaultChain  全局降级链（未命中 per-model 配置时使用）
 * @param chains        模型级降级链映射（key 为模型 ID，支持复合格式和纯 modelId）
 */
@ConfigurationProperties(prefix = "app.chat.fallback")
public record ChatFallbackProperties(
        Boolean enabled,
        int maxRetries,
        List<String> defaultChain,
        Map<String, List<String>> chains
) {

    /**
     * 紧凑构造器 — 提供合理默认值，防止 null 和非法值
     */
    public ChatFallbackProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (maxRetries <= 0) {
            maxRetries = 3;
        }
        if (defaultChain == null) {
            defaultChain = List.of();
        }
        if (chains == null) {
            chains = Map.of();
        }
    }
}
