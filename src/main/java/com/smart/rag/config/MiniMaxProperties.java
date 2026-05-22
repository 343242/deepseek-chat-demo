package com.smart.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MiniMax 模型厂商配置属性
 * <p>
 * 绑定 {@code spring.ai.minimax.*} 前缀，集中管理连接参数。
 * 与 {@link DeepSeekProperties}、{@link ZhipuProperties} 保持统一结构。
 * <p>
 * 纯数据持有，不包含任何业务逻辑。
 * 所有字段保证非 null，未配置时使用合理默认值。
 */
@ConfigurationProperties(prefix = "spring.ai.minimax")
public record MiniMaxProperties(
        String baseUrl,
        String apiKey,
        ChatOptions chat
) {
    public MiniMaxProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.minimaxi.com/v1";
        }
        if (chat == null) {
            chat = new ChatOptions(null, null, null, null);
        }
    }

    /**
     * MiniMax 聊天模型参数
     */
    public record ChatOptions(
            String model,
            Double temperature,
            Double topP,
            Integer maxTokens
    ) {
        public ChatOptions {
            if (model == null || model.isBlank()) {
                model = "MiniMax-M2.1";
            }
            if (temperature == null) {
                temperature = 0.7;
            }
        }
    }
}
