package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagEntityProperties;
import org.springframework.stereotype.Component;

/**
 * 实体管线 CHAT 客户端统一解析点。
 * <p>
 * 查询侧 seed 抽取、索引侧 chunk 抽取、描述压缩共用同一模型选择规则：
 * {@code app.rag.entity.extraction-model} 配置了 registry 候选 ID 则用之，否则走 CHAT 默认候选——
 * 保证专用抽取模型在查询/索引两侧语义一致。
 * <p>
 * llm-spi 约定：注入 {@link LlmClientRegistry} 是任何层允许的官方入口；配置了无效候选 ID 时
 * {@code registry.get} 直接抛 {@code RemoteException}（fail-fast），不得静默回落默认候选掩盖配置错误。
 */
@Component
public class EntityChatClientResolver {

    private final LlmClientRegistry llmClientRegistry;
    private final RagEntityProperties properties;

    public EntityChatClientResolver(LlmClientRegistry llmClientRegistry, RagEntityProperties properties) {
        this.llmClientRegistry = llmClientRegistry;
        this.properties = properties;
    }

    /**
     * 解析实体管线的 CHAT 客户端。
     *
     * @throws com.smart.rag.infrastructure.exception.RemoteException 候选 ID 无效时（fail-fast，不回落）
     */
    public ChatCapable resolve() {
        String model = properties.extractionModel();
        if (model != null && !model.isBlank()) {
            return llmClientRegistry.get(model, ChatCapable.class);
        }
        return llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class);
    }
}
