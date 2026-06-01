package com.smart.rag.infrastructure.ai.provider;

import com.smart.rag.common.errorcode.ErrorCode;
import com.smart.rag.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型 ID 路由解析器（单一职责）
 * <p>
 * 将用户请求中的 model 字段解析为 (providerId, modelId) 二元组。
 * <p>
 * 路由规则：
 * <ul>
 *   <li>"deepseek/deepseek-chat" → Route("deepseek", "deepseek-chat")</li>
 *   <li>"deepseek-chat" → Route(默认 provider, "deepseek-chat")（向后兼容）</li>
 *   <li>"zhipu/glm-4-air" → Route("zhipu", "glm-4-air")</li>
 * </ul>
 * <p>
 * 默认 provider 可通过 {@code model.router.default-provider} 配置，默认为 "deepseek"。
 */
@Component
public class ModelRouter {

    private static final String SEPARATOR = "/";

    private final String defaultProvider;

    public ModelRouter(
            @Value("${model.router.default-provider:deepseek}") String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    /**
     * 解析后的路由结果（不可变值对象）
     */
    public record Route(String providerId, String modelId) {
        /**
         * 重建完整的复合 model ID
         */
        public String toCompositeId() {
            return providerId + SEPARATOR + modelId;
        }
    }

    /**
     * 解析原始 model ID 为路由结果
     * <p>
     * 支持两种格式：
     * <ol>
     *   <li>复合格式: "{providerId}/{modelId}" — 精确路由</li>
     *   <li>简单格式: "{modelId}" — 默认路由到配置的 defaultProvider</li>
     * </ol>
     *
     * @param rawModelId 原始 model ID，不能为 null 或空
     * @return 路由结果
     * @throws BusinessException rawModelId 为 null 或空
     */
    public Route resolve(String rawModelId) {
        if (rawModelId == null || rawModelId.isBlank()) {
            throw new BusinessException(ErrorCode.MODEL_EMPTY);
        }

        int slashIndex = rawModelId.indexOf(SEPARATOR);
        if (slashIndex > 0 && slashIndex < rawModelId.length() - 1) {
            return new Route(rawModelId.substring(0, slashIndex),
                    rawModelId.substring(slashIndex + 1));
        }

        return new Route(defaultProvider, rawModelId);
    }
}
