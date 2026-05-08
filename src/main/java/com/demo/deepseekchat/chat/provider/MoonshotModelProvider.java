package com.demo.deepseekchat.chat.provider;

import com.demo.deepseekchat.chat.dto.ModelInfo;
import com.demo.deepseekchat.chat.entity.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Moonshot（月之暗面）模型厂商 Provider
 * <p>
 * Moonshot 兼容 OpenAI API 格式，因此使用 Spring AI 的 OpenAiApi / OpenAiChatModel 实现。
 * 构建链路：OpenAiApi(baseUrl, apiKey) → OpenAiChatOptions → OpenAiChatModel → ChatClient。
 * <p>
 * 通过环境变量 {@code MOONSHOT_API_KEY} 和 {@code MOONSHOT_BASE_URL} 配置连接参数。
 * Moonshot 不提供公开的 /models API，因此使用硬编码的模型列表。
 *
 * @see ModelProvider
 */
@Component
public class MoonshotModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(MoonshotModelProvider.class);

    /** Moonshot 可用模型列表（硬编码） */
    private static final List<ModelInfo> MODELS = List.of(
            new ModelInfo("moonshot-v1-8k", "model", Instant.now().getEpochSecond(), "moonshot"),
            new ModelInfo("moonshot-v1-32k", "model", Instant.now().getEpochSecond(), "moonshot"),
            new ModelInfo("moonshot-v1-128k", "model", Instant.now().getEpochSecond(), "moonshot")
    );

    private final String apiKey;
    private final String baseUrl;

    public MoonshotModelProvider(
            @Value("${MOONSHOT_API_KEY:}") String apiKey,
            @Value("${MOONSHOT_BASE_URL:https://api.moonshot.cn/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public String getProviderId() {
        return "moonshot";
    }

    @Override
    public String getDisplayName() {
        return "Moonshot";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 返回 Moonshot 的硬编码模型列表
     *
     * @return Moonshot 可用模型列表
     */
    @Override
    public List<ModelInfo> fetchModels() {
        return MODELS;
    }

    /**
     * 为指定 Moonshot 模型创建 ChatClient
     * <p>
     * 使用 OpenAI 兼容模式：OpenAiApi → OpenAiChatOptions → OpenAiChatModel → ChatClient。
     * Moonshot 的 API 格式与 OpenAI 兼容，因此直接复用 OpenAI 的实现类。
     *
     * @param modelId     模型 ID，如 "moonshot-v1-8k"、"moonshot-v1-128k"
     * @param temperature 可选温度参数，null 使用 Moonshot 默认值
     * @return 可用的 ChatClient 实例
     */
    @Override
    public ChatClient createClient(String modelId, Double temperature) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(modelId);
        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(optionsBuilder.build())
                .build();

        return ChatClient.builder(chatModel).build();
    }

    /**
     * 将统一 ModelParams 转换为 OpenAiChatOptions
     * <p>
     * 映射参数：temperature, maxTokens, topP, frequencyPenalty, presencePenalty。
     *
     * @param params 统一模型参数，可能为 null
     * @return OpenAiChatOptions 实例，params 为 null 时返回 null
     */
    @Override
    public ChatOptions buildOptions(ModelParams params) {
        if (params == null) {
            return null;
        }

        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        if (params.getTemperature() != null) builder.temperature(params.getTemperature());
        if (params.getMaxTokens() != null) builder.maxTokens(params.getMaxTokens());
        if (params.getTopP() != null) builder.topP(params.getTopP());
        if (params.getFrequencyPenalty() != null) builder.frequencyPenalty(params.getFrequencyPenalty());
        if (params.getPresencePenalty() != null) builder.presencePenalty(params.getPresencePenalty());

        return builder.build();
    }
}
