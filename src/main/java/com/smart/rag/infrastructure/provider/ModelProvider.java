package com.smart.rag.infrastructure.provider;

import com.smart.rag.infrastructure.model.ModelInfo;
import com.smart.rag.infrastructure.model.ModelOptionSettings;
import com.smart.rag.infrastructure.stream.ModelStreamException;
import com.smart.rag.infrastructure.stream.ModelStreamRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * 模型厂商抽象接口（策略模式）
 * <p>
 * 每个厂商（DeepSeek、智谱、MiniMax 等）提供一个实现。
 * 封装厂商差异：ChatClient 创建、ChatOptions 构建、模型列表拉取。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 每个 Provider 只负责自己厂商的模型管理</li>
 *   <li>OCP — 新增厂商只需新增实现类，零修改现有代码</li>
 *   <li>DIP — ChatService 依赖此接口，不依赖具体厂商类型</li>
 * </ul>
 * <p>
 * 封装边界：
 * <ul>
 *   <li>ChatOptions 类型差异（DeepSeekChatOptions vs ZhiPuAiChatOptions）不泄漏到外部</li>
 *   <li>API 调用细节（base-url、认证方式）不泄漏到外部</li>
 * </ul>
 */
public interface ModelProvider {

    /**
     * 厂商唯一标识
     * <p>
     * 用于 model ID 路由："deepseek/deepseek-chat" 中的 "deepseek" 部分。
     * 必须全局唯一，小写，不含斜杠。
     *
     * @return 厂商 ID，如 "deepseek"、"zhipu"、"minimax"
     */
    String getProviderId();

    /**
     * 厂商显示名称（面向用户）
     *
     * @return 如 "DeepSeek"、"智谱 AI"、"MiniMax"
     */
    String getDisplayName();

    /**
     * 该 Provider 是否可用
     * <p>
     * 检查条件：API Key 是否已配置、网络是否可达等。
     * 返回 false 时 ProviderRegistry 不注册此 Provider，不影响其他 Provider。
     * 应用启动不因缺少某个厂商的 API Key 而失败。
     *
     * @return true 表示该厂商已正确配置并可用
     */
    boolean isAvailable();

    /**
     * 从厂商 API 拉取可用模型列表
     * <p>
     * 如果厂商不提供模型列表 API（如智谱），则返回硬编码列表。
     * 调用失败时返回空列表，不抛异常（不影响其他 Provider 的模型注册）。
     *
     * @return 可用模型信息列表，不会返回 null
     */
    List<ModelInfo> fetchModels();

    /**
     * 为指定模型创建 ChatClient（工厂方法）
     * <p>
     * 封装厂商特有的 API 客户端创建逻辑：
     * DeepSeek → DeepSeekApi → DeepSeekChatModel → ChatClient
     * Zhipu → ZhiPuAiApi → ZhiPuAiChatModel → ChatClient
     * ...
     *
     * @param modelId     模型 ID，如 "deepseek-chat"、"glm-4-air"
     * @param temperature 可选温度参数，null 使用厂商默认值
     * @return 可用的 ChatClient 实例
     */
    ChatClient createClient(String modelId, Double temperature);

    /**
     * 为指定模型创建 ChatClient 及其底层 ChatModel（工厂方法）
     * <p>
     * 默认实现通过 {@link #createClient} 创建 ChatClient 后，再单独创建一次 ChatModel。
     * 各厂商实现可以覆写此方法以避免重复构建（复用同一个 ChatModel 实例）。
     *
     * @param modelId     模型 ID
     * @param temperature 可选温度参数，null 使用厂商默认值
     * @return 包含 ChatClient 和 ChatModel 的载体，ChatModel 可为 null（降级为字符估算）
     */
    default ClientAndModel createClientWithModel(String modelId, Double temperature) {
        ChatClient client = createClient(modelId, temperature);
        return new ClientAndModel(client, null);
    }

    /**
     * ChatClient + ChatModel 载体
     *
     * @param client    ChatClient 实例（不为 null）
     * @param chatModel 底层 ChatModel 实例（可为 null，降级为字符估算）
     */
    record ClientAndModel(ChatClient client, ChatModel chatModel) {}

    /**
     * 将统一的模型选项转换为厂商特定的 ChatOptions
     * <p>
     * 关键封装点：调用方（ChatService）不需要知道具体是
     * DeepSeekChatOptions 还是 ZhiPuAiChatOptions。
     * 每个厂商映射自己支持的参数，不支持的参数静默忽略。
     *
     * @param options 统一的模型选项，可能为 null
     * @return 厂商特定的 ChatOptions，params 为 null 时返回 null
     */
    ChatOptions buildOptions(ModelOptionSettings options);

    /**
     * 构建 OkHttp 流式请求配置。
     * <p>
     * 默认不支持，具体 Provider 按自己的 API 兼容性覆写。
     */
    default ModelStreamRequest createStreamRequest(ModelRouter.Route route,
                                                   String prompt,
                                                   ModelOptionSettings options) {
        throw new ModelStreamException("模型厂商暂不支持 OkHttp 流式调用: " + getProviderId());
    }
}
