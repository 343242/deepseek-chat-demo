package com.demo.deepseekchat.chat.provider;

import com.demo.deepseekchat.chat.dto.ModelInfo;
import com.demo.deepseekchat.chat.dto.ModelsResponse;
import com.demo.deepseekchat.chat.entity.ModelParams;
import com.demo.deepseekchat.config.DeepSeekProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
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
public class DeepSeekModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekModelProvider.class);

    private final DeepSeekProperties properties;
    private final RestClient restClient;

    public DeepSeekModelProvider(DeepSeekProperties properties,
                                 @Qualifier("deepSeekRestClient") RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
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
     * 调用失败时返回空列表，不影响其他 Provider 的模型注册。
     *
     * @return 模型信息列表，调用失败时返回空列表
     */
    @Override
    public List<ModelInfo> fetchModels() {
        try {
            ModelsResponse response = restClient.get()
                    .uri("/models")
                    .retrieve()
                    .body(ModelsResponse.class);
            return response != null && response.data() != null
                    ? response.data()
                    : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to fetch DeepSeek models: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 为指定 DeepSeek 模型创建 ChatClient
     * <p>
     * 构建链路：DeepSeekApi → DeepSeekChatOptions → DeepSeekChatModel → ChatClient。
     * temperature 参数优先级：传入参数 > 配置文件默认值。
     *
     * @param modelId     模型 ID，如 "deepseek-chat"、"deepseek-reasoner"
     * @param temperature 可选温度参数，null 使用配置默认值
     * @return 可用的 ChatClient 实例
     */
    @Override
    public ChatClient createClient(String modelId, Double temperature) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .completionsPath("/chat/completions")
                .build();

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

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(optionsBuilder.build())
                .build();

        return ChatClient.builder(chatModel).build();
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
    public ChatOptions buildOptions(ModelParams params) {
        if (params == null) {
            return null;
        }

        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder();
        if (params.getTemperature() != null) builder.temperature(params.getTemperature());
        if (params.getMaxTokens() != null) builder.maxTokens(params.getMaxTokens());
        if (params.getTopP() != null) builder.topP(params.getTopP());
        if (params.getFrequencyPenalty() != null) builder.frequencyPenalty(params.getFrequencyPenalty());
        if (params.getPresencePenalty() != null) builder.presencePenalty(params.getPresencePenalty());

        return builder.build();
    }
}
