package com.demo.deepseekchat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek 相关配置属性
 * <p>
 * 绑定 spring.ai.deepseek.* 前缀，集中管理连接参数。
 * 纯数据持有，不包含任何业务逻辑。
 * <p>
 * 所有字段保证非 null，未配置时使用合理默认值。
 */
@ConfigurationProperties(prefix = "spring.ai.deepseek")
public record DeepSeekProperties(
        String baseUrl,
        String apiKey,
        ChatOptions chat
) {
    public DeepSeekProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }
        // 保证 chat 永远不为 null，避免 ChatClientFactory 中的 NPE
        if (chat == null) {
            chat = new ChatOptions(null, null, null, null);
        }
    }

    /**
     * DeepSeek 聊天模型参数
     */
    public record ChatOptions(
            String model,
            Double temperature,
            Double topP,
            Integer maxTokens
    ) {
        public ChatOptions {
            if (model == null || model.isBlank()) {
                model = "deepseek-chat";
            }
            if (temperature == null) {
                temperature = 0.7;
            }
        }
    }
}
