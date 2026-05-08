package com.demo.chat.chat.provider;

import com.demo.chat.chat.dto.ModelInfo;
import com.demo.chat.chat.dto.ModelsResponse;
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
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * MiniMax 模型厂商 Provider
 * <p>
 * 封装 MiniMax 的 ChatClient 创建、ChatOptions 构建和模型列表拉取。
 * MiniMax 兼容 OpenAI API 规范，通过 {@code GET /v1/models} 动态获取模型列表。
 * 拉取失败时回退到硬编码的默认模型列表，保证服务可用性。
 * <p>
 * 通过 {@code spring.ai.minimax.api-key} 和 {@code spring.ai.minimax.base-url} 配置连接参数。
 * MiniMaxApi 构造函数签名为 {@code MiniMaxApi(apiKey, baseUrl)}。
 *
 * @see ModelProvider
 */
@Component
public class MiniMaxModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxModelProvider.class);

    private static final String DEFAULT_BASE_URL = "https://api.minimaxi.com/v1";

    /** MiniMax 兜底模型列表（API 拉取失败时使用） */
    private static final List<ModelInfo> FALLBACK_MODELS = List.of(
            new ModelInfo("MiniMax-M2.7", "model", 0L, "minimax"),
            new ModelInfo("MiniMax-M2.5", "model", 0L, "minimax"),
            new ModelInfo("MiniMax-M2.1", "model", 0L, "minimax")
    );

    private final String apiKey;
    private final String baseUrl;
    private final RestClient restClient;

    public MiniMaxModelProvider(
            @Value("${spring.ai.minimax.api-key:}") String apiKey,
            @Value("${spring.ai.minimax.base-url:#{null}}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
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
     * 从 MiniMax /v1/models API 拉取可用模型列表
     * <p>
     * MiniMax 兼容 OpenAI API 规范，GET /v1/models 返回标准格式。
     * 拉取失败时回退到硬编码的 {@link #FALLBACK_MODELS}，保证服务可用性。
     *
     * @return 模型信息列表
     */
    @Override
    public List<ModelInfo> fetchModels() {
        try {
            ModelsResponse response = restClient.get()
                    .uri("/models")
                    .retrieve()
                    .body(ModelsResponse.class);
            if (response != null && response.data() != null && !response.data().isEmpty()) {
                log.info("Fetched {} models from MiniMax API", response.data().size());
                return response.data();
            }
            log.warn("MiniMax API returned empty response, using fallback");
            return FALLBACK_MODELS;
        } catch (Exception e) {
            log.warn("Failed to fetch MiniMax models: {}, using fallback", e.getMessage());
            return FALLBACK_MODELS;
        }
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
        MiniMaxApi api = new MiniMaxApi(apiKey, baseUrl);

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
