package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.entity.RagEntity;
import com.smart.rag.rag.mapper.EntityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体 embedding 服务
 * <p>
 * 职责：聚合后 description 的批量 embed → 更新 rag_entity.embedding
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class EntityEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EntityEmbeddingService.class);

    private static final String COMPRESSION_SYSTEM_PROMPT =
            "请用 1-2 句话简洁概括以下文本的核心内容，保留关键信息，去除冗余。";

    private final LlmClientRegistry llmClientRegistry;
    private final EntityMapper entityMapper;
    private final RagEntityProperties properties;

    public EntityEmbeddingService(LlmClientRegistry llmClientRegistry,
                                   EntityMapper entityMapper,
                                   RagEntityProperties properties) {
        this.llmClientRegistry = llmClientRegistry;
        this.entityMapper = entityMapper;
        this.properties = properties;
    }

    /**
     * 批量嵌入实体描述
     *
     * @param entities 需要嵌入的实体列表
     */
    public void embedEntities(List<RagEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }

        // 过滤有效实体（description 非空）
        List<RagEntity> toEmbed = entities.stream()
                .filter(e -> e.getDescription() != null && !e.getDescription().isEmpty())
                .toList();

        if (toEmbed.isEmpty()) {
            log.debug("No entities need embedding (all empty description)");
            return;
        }

        List<String> texts = compressLongDescriptions(toEmbed);

        EmbeddingCapable embeddingClient = llmClientRegistry.getDefault(
                LlmCapability.EMBEDDING, EmbeddingCapable.class);
        embedInBatches(toEmbed, texts, embeddingClient);

        log.info("Embedded {} entities (batchSize={})", toEmbed.size(), properties.embeddingBatchSize());
    }

    /**
     * 对超长 description 进行 LLM 压缩（无 CHAT client 时保持原文）。
     *
     * @return 与 toEmbed 一一对应的待嵌入文本
     */
    private List<String> compressLongDescriptions(List<RagEntity> toEmbed) {
        int maxLen = properties.descriptionMaxLength();
        ChatCapable chatClient = getChatClientSafe();
        List<String> texts = new ArrayList<>(toEmbed.size());
        for (RagEntity entity : toEmbed) {
            String text = entity.getDescription();
            if (text.length() > maxLen && chatClient != null) {
                text = compressDescription(text, chatClient);
            }
            texts.add(text);
        }
        return texts;
    }

    /**
     * 分批 embed 并回写（failure isolation per batch：单批失败继续下一批）
     */
    private void embedInBatches(List<RagEntity> toEmbed, List<String> texts,
                                EmbeddingCapable embeddingClient) {
        int batchSize = properties.embeddingBatchSize();
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            try {
                List<float[]> embeddings =
                        embeddingClient.embedBatch(texts.subList(i, end), EmbeddingType.DOCUMENT);
                updateEmbeddings(toEmbed.subList(i, end), embeddings);
            } catch (Exception e) {
                log.error("Failed to embed entity batch [{}-{}]: {}", i, end, e);
            }
        }
    }

    /**
     * 批量回写 embedding（过滤空结果）
     */
    private void updateEmbeddings(List<RagEntity> batchEntities, List<float[]> embeddings) {
        List<EntityMapper.EmbeddingUpdate> updates = new ArrayList<>(batchEntities.size());
        for (int j = 0; j < batchEntities.size() && j < embeddings.size(); j++) {
            float[] embedding = embeddings.get(j);
            if (embedding != null && embedding.length > 0) {
                updates.add(new EntityMapper.EmbeddingUpdate(batchEntities.get(j).getId(), embedding));
            }
        }
        if (!updates.isEmpty()) {
            entityMapper.updateEmbeddingBatch(updates);
        }
    }

    /**
     * 使用 LLM 压缩过长描述
     */
    private String compressDescription(String text, ChatCapable chatClient) {
        try {
            ChatRequest request = ChatRequest.withSystem(COMPRESSION_SYSTEM_PROMPT, text);
            LlmResponse response = chatClient.chat(request);
            String compressed = response.content();
            if (compressed != null && !compressed.isEmpty()) {
                return compressed;
            }
        } catch (Exception e) {
            log.warn("Failed to compress entity description, using truncated original: {}", e.getMessage());
        }
        // 回退：截断到 maxLen
        return text.length() > properties.descriptionMaxLength()
                ? text.substring(0, properties.descriptionMaxLength())
                : text;
    }

    /**
     * 安全获取 ChatCapable（用于 description 压缩）
     */
    @SuppressWarnings("unchecked")
    private ChatCapable getChatClientSafe() {
        try {
            return llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class);
        } catch (Exception e) {
            log.warn("No CHAT client available for description compression: {}", e.getMessage());
            return null;
        }
    }
}
