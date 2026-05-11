package com.demo.chat.chat.fallback;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 聊天兜底策略配置
 * <p>
 * 当主模型调用失败时，按配置的降级链依次尝试备选模型，
 * 直到成功或链耗尽。
 * <p>
 * 配置示例：
 * <pre>
 * app:
 *   chat:
 *     fallback:
 *       enabled: true
 *       max-attempts: 3
 *       default-chain:
 *         - deepseek/deepseek-v4-flash
 *         - zhipu/glm-4.7-flash
 *         - minimax/MiniMax-M2.1
 * </pre>
 *
 * @param enabled      是否启用兜底策略，默认 true
 * @param maxAttempts  最大尝试次数（含原始请求），默认 3
 * @param defaultChain 降级链（有序备选模型 ID 列表，建议使用 provider/model 复合格式）
 */
@ConfigurationProperties(prefix = "app.chat.fallback")
public record ChatFallbackProperties(
        boolean enabled,
        int maxAttempts,
        List<String> defaultChain
) {

    /**
     * 紧凑构造器 — 提供合理默认值，防止 null 和非法值
     */
    public ChatFallbackProperties {
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (defaultChain == null) {
            defaultChain = List.of();
        }
    }
}
