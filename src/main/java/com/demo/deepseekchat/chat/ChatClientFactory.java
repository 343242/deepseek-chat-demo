package com.demo.deepseekchat.chat;

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
     * 为指定模型 ID 创建 ChatClient
     */
    public ChatClient create(String modelId) {
        return create(modelId, properties.chat().temperature());
    }

    /**
     * 为指定模型 ID 创建 ChatClient，自定义 temperature
     */
    public ChatClient create(String modelId, Double temperature) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .completionsPath("/chat/completions")
                .build();

        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
                .model(modelId);

        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }
        DeepSeekProperties.ChatOptions chatOpts = properties.chat();
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
