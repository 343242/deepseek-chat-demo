package com.smart.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智谱 AI（ZhiPu）模型厂商配置属性
 * <p>
 * 绑定 {@code spring.ai.zhipuai.*} 前缀，集中管理连接参数。
 * 与 {@link DeepSeekProperties}、{@link MiniMaxProperties} 保持统一结构。
 * <p>
 * 纯数据持有，不包含任何业务逻辑。
 * 所有字段保证非 null，未配置时使用合理默认值。
 */
@ConfigurationProperties(prefix = "spring.ai.zhipuai")
public record ZhipuProperties(
        String baseUrl,
        String apiKey,
        ChatOptions chat
) {
    public ZhipuProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        }
        if (chat == null) {
            chat = new ChatOptions(null, null, null, null);
        }
    }

    /**
     * 智谱 AI 聊天模型参数
     */
    public record ChatOptions(
            String model,
            Double temperature,
            Double topP,
            Integer maxTokens
    ) {
        public ChatOptions {
            if (model == null || model.isBlank()) {
                model = "glm-4.7";
            }
            if (temperature == null) {
                temperature = 0.7;
            }
        }
    }
}
