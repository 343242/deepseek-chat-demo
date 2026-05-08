package com.demo.chat.chat.provider;

import com.demo.chat.chat.dto.ModelInfo;
import com.demo.chat.chat.entity.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MiniMax 模型厂商 Provider
 * <p>
 * 封装 MiniMax 的 ChatClient 创建、ChatOptions 构建和模型列表管理。
 * MiniMax 不提供公开的 /models API，因此使用硬编码的模型列表。
 * <p>
 * 通过 {@code spring.ai.minimax.api-key} 配置连接参数。
 * MiniMaxApi 构造函数签名为 {@code MiniMaxApi(apiKey, baseUrl)}。
 *
 * @see ModelProvider
 */
@Component
public class MiniMaxModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxModelProvider.class);

    private static final String BASE_URL = "https://api.minimax.chat/v1";

    /** MiniMax 可用模型列表（硬编码） */
    private static final List<ModelInfo> MODELS = List.of(
            new ModelInfo("MiniMax-Text-01", "model", 0L, "minimax"),
            new ModelInfo("abab6.5g-chat", "model", 0L, "minimax"),
            new ModelInfo("abab6.5s-chat", "model", 0L, "minimax")
    );

    private final String apiKey;

    public MiniMaxModelProvider(@Value("${spring.ai.minimax.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String getProviderId() {
        return "minimax";
    }

    @Override
    public String getDisplayName() {
        return "MiniMax";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 返回 MiniMax 的硬编码模型列表
     *
     * @return MiniMax 可用模型列表
     */
    @Override
    public List<ModelInfo> fetchModels() {
        return MODELS;
    }

    /**
     * 为指定 MiniMax 模型创建 ChatClient
     * <p>
     * 构建链路：MiniMaxApi(apiKey, baseUrl) → MiniMaxChatOptions → MiniMaxChatModel → ChatClient。
     *
     * @param modelId     模型 ID，如 "MiniMax-Text-01"、"abab6.5g-chat"
     * @param temperature 可选温度参数，null 使用 MiniMax 默认值
     * @return 可用的 ChatClient 实例
     */
    @Override
    public ChatClient createClient(String modelId, Double temperature) {
        MiniMaxApi api = new MiniMaxApi(apiKey, BASE_URL);

        MiniMaxChatOptions.Builder optionsBuilder = MiniMaxChatOptions.builder()
                .model(modelId);
        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }

        MiniMaxChatModel chatModel = new MiniMaxChatModel(api, optionsBuilder.build());

        return ChatClient.builder(chatModel).build();
    }

    /**
     * 将统一 ModelParams 转换为 MiniMaxChatOptions
     * <p>
     * 映射参数：temperature, maxTokens, topP, frequencyPenalty, presencePenalty。
     *
     * @param params 统一模型参数，可能为 null
     * @return MiniMaxChatOptions 实例，params 为 null 时返回 null
     */
    @Override
    public ChatOptions buildOptions(ModelParams params) {
        if (params == null) {
            return null;
        }

        MiniMaxChatOptions.Builder builder = MiniMaxChatOptions.builder();
        if (params.getTemperature() != null) builder.temperature(params.getTemperature());
        if (params.getMaxTokens() != null) builder.maxTokens(params.getMaxTokens());
        if (params.getTopP() != null) builder.topP(params.getTopP());
        if (params.getFrequencyPenalty() != null) builder.frequencyPenalty(params.getFrequencyPenalty());
        if (params.getPresencePenalty() != null) builder.presencePenalty(params.getPresencePenalty());

        return builder.build();
    }
}
