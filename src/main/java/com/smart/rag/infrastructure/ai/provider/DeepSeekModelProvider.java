package com.smart.rag.infrastructure.ai.provider;

import com.smart.rag.infrastructure.ai.model.ModelInfo;
import com.smart.rag.infrastructure.ai.model.ModelsResponse;
import com.smart.rag.infrastructure.ai.model.ModelOptionSettings;
import com.smart.rag.config.DeepSeekProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * DeepSeek 模型厂商 Provider
 * <p>
 * 封装 DeepSeek API 的 ChatClient 创建、ChatOptions 构建和模型列表拉取。
 * 通过 DeepSeekProperties 获取连接配置，复用已配置的 deepSeekRestClient 调用 /models 端点。
 *
 * @see ModelProvider
 * @see DeepSeekProperties
 */
@Component
public class DeepSeekModelProvider extends AbstractModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekModelProvider.class);

    /** DeepSeek 兜底模型列表（API 拉取失败时使用） */
    private static final List<ModelInfo> FALLBACK_MODELS = List.of(
            new ModelInfo("deepseek-v4-flash", "model", 0L, "deepseek"),
            new ModelInfo("deepseek-v4-pro", "model", 0L, "deepseek")
    );

    private final DeepSeekProperties properties;
    private final RestClient restClient;
    private final DeepSeekApi sharedApi;

    public DeepSeekModelProvider(DeepSeekProperties properties,
                                 @Qualifier("deepSeekRestClient") RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
        this.sharedApi = DeepSeekApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .completionsPath("/chat/completions")
                .build();
    }

    @Override
    public String getProviderId() {
        return "deepseek";
    }

    @Override
    public String getDisplayName() {
        return "DeepSeek";
    }

    @Override
    public boolean isAvailable() {
        return properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    /**
     * 从 DeepSeek /models API 拉取可用模型列表
     * <p>
     * 调用失败时回退到硬编码的 {@link #FALLBACK_MODELS}，保证服务可用性。
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
            if (response != null && response.data() != null) {
                return response.data();
            }
            log.warn("DeepSeek API returned empty response, using fallback");
            return FALLBACK_MODELS;
        } catch (Exception e) {
            log.warn("Failed to fetch DeepSeek models, using fallback", e);
            return FALLBACK_MODELS;
        }
    }

    /**
     * 构建 DeepSeek 特定的 ChatModel。
     * <p>
     * 构建链路：DeepSeekApi + DeepSeekChatOptions -> DeepSeekChatModel。
     * temperature 参数优先级：传入参数 > 配置文件默认值。
     *
     * @param modelId     模型 ID，如 "deepseek-chat"、"deepseek-reasoner"
     * @param temperature 可选温度参数，null 使用配置默认值
     * @return DeepSeekChatModel 实例
     */
    @Override
    protected ChatModel buildChatModel(String modelId, Double temperature) {
        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
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

        return DeepSeekChatModel.builder()
                .deepSeekApi(sharedApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    /**
     * 将统一 ModelParams 转换为 DeepSeekChatOptions
     * <p>
     * 映射参数：temperature, maxTokens, topP, frequencyPenalty, presencePenalty。
     * 不支持的参数静默忽略。
     *
     * @param params 统一模型参数，可能为 null
     * @return DeepSeekChatOptions 实例，params 为 null 时返回 null
     */
    @Override
    public ChatOptions buildOptions(ModelOptionSettings options) {
        if (options == null) {
            return null;
        }

        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder();
        if (options.temperature() != null) builder.temperature(options.temperature());
        if (options.maxTokens() != null) builder.maxTokens(options.maxTokens());
        if (options.topP() != null) builder.topP(options.topP());
        if (options.frequencyPenalty() != null) builder.frequencyPenalty(options.frequencyPenalty());
        if (options.presencePenalty() != null) builder.presencePenalty(options.presencePenalty());

        return builder.build();
    }
}
