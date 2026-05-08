package com.demo.deepseekchat.chat.provider;

import com.demo.deepseekchat.chat.dto.ModelInfo;
import com.demo.deepseekchat.chat.entity.ModelParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 智谱 AI（ZhiPu）模型厂商 Provider
 * <p>
 * 封装智谱 AI 的 ChatClient 创建、ChatOptions 构建和模型列表管理。
 * 智谱不提供 /models API，因此使用硬编码的模型列表。
 * <p>
 * 通过 {@code spring.ai.zhipuai.api-key} 和 {@code spring.ai.zhipuai.base-url} 配置连接参数。
 *
 * @see ModelProvider
 */
@Component
public class ZhipuModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ZhipuModelProvider.class);

    /** 智谱 AI 可用模型列表（硬编码，因智谱无 /models API） */
    private static final List<ModelInfo> MODELS = List.of(
            new ModelInfo("glm-4-air", "model", Instant.now().getEpochSecond(), "zhipuai"),
            new ModelInfo("glm-4-flash", "model", Instant.now().getEpochSecond(), "zhipuai"),
            new ModelInfo("glm-4-plus", "model", Instant.now().getEpochSecond(), "zhipuai"),
            new ModelInfo("glm-4-long", "model", Instant.now().getEpochSecond(), "zhipuai")
    );

    private final String apiKey;
    private final String baseUrl;

    public ZhipuModelProvider(
            @Value("${spring.ai.zhipuai.api-key:}") String apiKey,
            @Value("${spring.ai.zhipuai.base-url:https://open.bigmodel.cn/api/paas}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public String getProviderId() {
        return "zhipu";
    }

    @Override
    public String getDisplayName() {
        return "智谱 AI";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 返回智谱 AI 的硬编码模型列表
     * <p>
     * 智谱不提供公开的 /models API，因此直接返回预定义列表。
     *
     * @return 智谱 AI 可用模型列表
     */
    @Override
    public List<ModelInfo> fetchModels() {
        return MODELS;
    }

    /**
     * 为指定智谱模型创建 ChatClient
     * <p>
     * 构建链路：ZhiPuAiApi → ZhiPuAiChatOptions → ZhiPuAiChatModel → ChatClient。
     *
     * @param modelId     模型 ID，如 "glm-4-air"、"glm-4-flash"
     * @param temperature 可选温度参数，null 使用智谱默认值
     * @return 可用的 ChatClient 实例
     */
    @Override
    public ChatClient createClient(String modelId, Double temperature) {
        ZhiPuAiApi api = ZhiPuAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        ZhiPuAiChatOptions.Builder optionsBuilder = ZhiPuAiChatOptions.builder()
                .model(modelId);
        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }

        ZhiPuAiChatModel chatModel = new ZhiPuAiChatModel(api, optionsBuilder.build());

        return ChatClient.builder(chatModel).build();
    }

    /**
     * 将统一 ModelParams 转换为 ZhiPuAiChatOptions
     * <p>
     * 映射参数：temperature, maxTokens, topP。
     * 智谱不支持 frequencyPenalty 和 presencePenalty，静默忽略。
     *
     * @param params 统一模型参数，可能为 null
     * @return ZhiPuAiChatOptions 实例，params 为 null 时返回 null
     */
    @Override
    public ChatOptions buildOptions(ModelParams params) {
        if (params == null) {
            return null;
        }

        ZhiPuAiChatOptions.Builder builder = ZhiPuAiChatOptions.builder();
        if (params.getTemperature() != null) builder.temperature(params.getTemperature());
        if (params.getMaxTokens() != null) builder.maxTokens(params.getMaxTokens());
        if (params.getTopP() != null) builder.topP(params.getTopP());

        return builder.build();
    }
}
