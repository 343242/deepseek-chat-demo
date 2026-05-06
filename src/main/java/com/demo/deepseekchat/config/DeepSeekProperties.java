package com.demo.deepseekchat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek 相关配置属性
 * <p>
 * 绑定 spring.ai.deepseek.* 前缀，集中管理连接参数。
 * 纯数据持有，不包含任何业务逻辑。
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
    }

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
