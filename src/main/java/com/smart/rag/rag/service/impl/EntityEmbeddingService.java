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
import java.util.stream.IntStream;

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

        int batchSize = properties.getEmbeddingBatchSize();
        int maxLen = properties.getDescriptionMaxLength();

        // 过滤已有 embedding 的实体
        List<RagEntity> toEmbed = entities.stream()
                .filter(e -> e.getEmbedding() == null || e.getEmbedding().length == 0)
                .filter(e -> e.getDescription() != null && !e.getDescription().isEmpty())
                .toList();

        if (toEmbed.isEmpty()) {
            log.debug("No entities need embedding (all already embedded or empty description)");
            return;
        }

        // 对超长 description 进行压缩
        List<String> texts = new ArrayList<>(toEmbed.size());
        ChatCapable chatClient = getChatClientSafe();

        for (RagEntity entity : toEmbed) {
            String text = entity.getDescription();
            if (text.length() > maxLen && chatClient != null) {
                text = compressDescription(text, chatClient);
            }
            texts.add(text);
        }

        // 分批 embed
        EmbeddingCapable embeddingClient = llmClientRegistry.getDefault(
                LlmCapability.EMBEDDING, EmbeddingCapable.class);

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            List<RagEntity> batchEntities = toEmbed.subList(i, end);

            try {
                List<float[]> embeddings = embeddingClient.embedBatch(batch, EmbeddingType.DOCUMENT);

                // 逐条更新
                for (int j = 0; j < batchEntities.size() && j < embeddings.size(); j++) {
                    RagEntity entity = batchEntities.get(j);
                    float[] embedding = embeddings.get(j);
                    if (embedding != null && embedding.length > 0) {
                        entityMapper.updateEmbedding(entity.getId(), embedding);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to embed entity batch [{}-{}]: {}", i, end, e.getMessage());
                // 继续下一批（failure isolation per batch）
            }
        }

        log.info("Embedded {} entities (batchSize={})", toEmbed.size(), batchSize);
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
        return text.length() > properties.getDescriptionMaxLength()
                ? text.substring(0, properties.getDescriptionMaxLength())
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
