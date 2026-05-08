package com.demo.deepseekchat.chat.client;

import com.demo.deepseekchat.config.DeepSeekProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.stereotype.Component;

/**
 * ChatClient 构建工厂
 * <p>
 * 封装 DeepSeekApi → DeepSeekChatOptions → DeepSeekChatModel → ChatClient 的构建流程。
 * 单一职责：只负责创建，不管存储。
 */
@Component
public class ChatClientFactory {

    private final DeepSeekProperties properties;

    public ChatClientFactory(DeepSeekProperties properties) {
        this.properties = properties;
    }

    /**
     * 为指定模型 ID 创建 ChatClient（使用默认 temperature）
     */
    public ChatClient create(String modelId) {
        return create(modelId, null);
    }

    /**
     * 为指定模型 ID 创建 ChatClient，可自定义 temperature
     */
    public ChatClient create(String modelId, Double temperature) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .completionsPath("/chat/completions")
                .build();

        // properties.chat() 保证非 null（DeepSeekProperties 构造函数已兜底）
        DeepSeekProperties.ChatOptions chatOpts = properties.chat();

        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
                .model(modelId);

        // temperature：优先使用参数传入，其次使用配置
        Double temp = temperature != null ? temperature : chatOpts.temperature();
        if (temp != null) {
            optionsBuilder.temperature(temp);
        }
        if (chatOpts.topP() != null) {
            optionsBuilder.topP(chatOpts.topP());
        }
        if (chatOpts.maxTokens() != null) {
            optionsBuilder.maxTokens(chatOpts.maxTokens());
        }

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(optionsBuilder.build())
                .build();

        return ChatClient.builder(chatModel).build();
    }
}
