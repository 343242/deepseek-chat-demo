package com.smart.rag.infrastructure.ai.provider;

import com.smart.rag.chat.dto.ModelInfo;
import com.smart.rag.chat.dto.ModelsResponse;
import com.smart.rag.chat.entity.ModelParams;
import com.smart.rag.config.MiniMaxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.minimax.MiniMaxChatModel;
import org.springframework.ai.minimax.MiniMaxChatOptions;
import org.springframework.ai.minimax.api.MiniMaxApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * MiniMax 模型厂商 Provider
 * <p>
 * 封装 MiniMax 的 ChatClient 创建、ChatOptions 构建和模型列表拉取。
 * MiniMax 兼容 OpenAI API 规范，通过 {@code GET /v1/models} 动态获取模型列表。
 * 拉取失败时回退到硬编码的默认模型列表，保证服务可用性。
 * <p>
 * 通过 {@link MiniMaxProperties} 获取连接配置，与 DeepSeek、Zhipu Provider 保持统一的配置模式。
 *
 * @see ModelProvider
 * @see MiniMaxProperties
 */
@Component
public class MiniMaxModelProvider extends AbstractModelProvider {

    private static final Logger log = LoggerFactory.getLogger(MiniMaxModelProvider.class);

    /** MiniMax 兜底模型列表（API 拉取失败时使用） */
    private static final List<ModelInfo> FALLBACK_MODELS = List.of(
            new ModelInfo("MiniMax-M2.7", "model", 0L, "minimax"),
            new ModelInfo("MiniMax-M2.5", "model", 0L, "minimax"),
            new ModelInfo("MiniMax-M2.1", "model", 0L, "minimax")
    );

    private final MiniMaxProperties properties;
    private final RestClient restClient;
    private final MiniMaxApi sharedApi;

    public MiniMaxModelProvider(MiniMaxProperties properties,
                                @Qualifier("miniMaxRestClient") RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
        this.sharedApi = new MiniMaxApi(properties.apiKey(), properties.baseUrl());
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
        return properties.apiKey() != null && !properties.apiKey().isBlank();
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
     * 构建 MiniMax 特定的 ChatModel。
     * <p>
     * 构建链路：MiniMaxApi + MiniMaxChatOptions -> MiniMaxChatModel。
     * temperature 参数优先级：传入参数 > 配置文件默认值。
     *
     * @param modelId     模型 ID，如 "MiniMax-Text-01"、"abab6.5g-chat"
     * @param temperature 可选温度参数，null 使用 MiniMax 默认值
     * @return MiniMaxChatModel 实例
     */
    @Override
    protected ChatModel buildChatModel(String modelId, Double temperature) {
        MiniMaxChatOptions.Builder optionsBuilder = MiniMaxChatOptions.builder()
                .model(modelId);

        Double temp = temperature != null ? temperature : properties.chat().temperature();
        if (temp != null) {
            optionsBuilder.temperature(temp);
        }
        if (properties.chat().topP() != null) {
            optionsBuilder.topP(properties.chat().topP());
        }
        if (properties.chat().maxTokens() != null) {
            optionsBuilder.maxTokens(properties.chat().maxTokens());
        }

        return new MiniMaxChatModel(sharedApi, optionsBuilder.build());
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
