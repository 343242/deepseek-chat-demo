package com.smart.rag.infrastructure.ai.provider;

import com.smart.rag.infrastructure.ai.model.ModelOptionSettings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 模型厂商抽象基类（模板方法模式）
 * <p>
 * 提取 DeepSeek / Zhipu / MiniMax 三个 Provider 中重复的
 * createClient / createClientWithModel 流程。
 * <p>
 * 三个方法都遵循相同的构建链路：
 * <ol>
 *   <li>子类通过 {@link #buildChatModel} 构建厂商特定的 ChatModel</li>
 *   <li>基类统一从 ChatModel 创建 ChatClient</li>
 * </ol>
 * <p>
 * 子类只需实现 {@link #buildChatModel} 和 {@link #buildOptions}，
 * isAvailable / fetchModels / getProviderId / getDisplayName
 * 仍由各子类自行定义（差异较大，不适合模板化）。
 *
 * @see ModelProvider
 */
public abstract class AbstractModelProvider implements ModelProvider {

    /**
     * 由子类构建厂商特定的 ChatModel。
     * <p>
     * 子类负责根据 modelId 和 temperature 解析参数、构建 options、创建 ChatModel。
     *
     * @param modelId     模型 ID
     * @param temperature 温度参数（可能为 null，由子类决定默认值）
     * @return 厂商特定的 ChatModel 实例
     */
    protected abstract ChatModel buildChatModel(String modelId, Double temperature);

    /**
     * 由子类将统一 ModelParams 转换为厂商特定的 ChatOptions。
     * <p>
     * 不支持的参数由子类静默忽略。
     *
     * @param params 统一模型参数，可能为 null
     * @return 厂商特定的 ChatOptions，params 为 null 时返回 null
     */
    @Override
    public abstract ChatOptions buildOptions(ModelOptionSettings options);

    /**
     * 为指定模型创建 ChatClient
     * <p>
     * 通过 {@link #buildChatModel} 获取厂商特定的 ChatModel，
     * 再统一构建 ChatClient。
     *
     * @param modelId     模型 ID
     * @param temperature 可选温度参数，null 使用厂商默认值
     * @return 可用的 ChatClient 实例
     */
    @Override
    public ChatClient createClient(String modelId, Double temperature) {
        ChatModel chatModel = buildChatModel(modelId, temperature);
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 创建 ChatClient 并复用已构建的 ChatModel，避免重复构建。
     * <p>
     * 覆盖接口中的 default 实现以返回实际的 ChatModel 实例。
     *
     * @param modelId     模型 ID
     * @param temperature 可选温度参数，null 使用厂商默认值
     * @return 包含 ChatClient 和 ChatModel 的载体
     */
    @Override
    public ClientAndModel createClientWithModel(String modelId, Double temperature) {
        ChatModel chatModel = buildChatModel(modelId, temperature);
        ChatClient client = ChatClient.builder(chatModel).build();
        return new ClientAndModel(client, chatModel);
    }
}
