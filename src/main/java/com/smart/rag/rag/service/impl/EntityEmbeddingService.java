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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 实体 embedding 服务
 * <p>
 * 职责：聚合后 description 的批量 embed → 更新 rag_entity.embedding。
 * <p>
 * V30 §3.2.1：DB 写回（{@code updateEmbeddingBatch}，rag_entity 多行写）收编进
 * {@link ScopeLockTemplate} advisory 短事务（毫秒~百毫秒级，LLM 调用留在锁外）；
 * 写回重试耗尽 → 异常传播 → {@code extractAndIndex} 异常退出 → 完成标记不写 →
 * §6.2 次日重链接补（幂等；项目无补嵌调度，已核实 selectEntitiesNeedingEmbedding 无调用方）。
 */
@Service
public class EntityEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EntityEmbeddingService.class);

    private static final String COMPRESSION_SYSTEM_PROMPT =
            "请用 1-2 句话简洁概括以下文本的核心内容，保留关键信息，去除冗余。";

    private final LlmClientRegistry llmClientRegistry;
    private final EntityMapper entityMapper;
    private final RagEntityProperties properties;
    private final ScopeLockTemplate scopeLockTemplate;
    private final LockRetryExecutor lockRetryExecutor;
    private final TransactionTemplate transactionTemplate;

    public EntityEmbeddingService(LlmClientRegistry llmClientRegistry,
                                   EntityMapper entityMapper,
                                   RagEntityProperties properties,
                                   ScopeLockTemplate scopeLockTemplate,
                                   LockRetryExecutor lockRetryExecutor,
                                   TransactionTemplate transactionTemplate) {
        this.llmClientRegistry = llmClientRegistry;
        this.entityMapper = entityMapper;
        this.properties = properties;
        this.scopeLockTemplate = scopeLockTemplate;
        this.lockRetryExecutor = lockRetryExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 批量嵌入实体描述
     *
     * @param entities 需要嵌入的实体列表（同一 scope——来自单文档抽取）
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
     * 分批 embed 并回写（LLM 调用 failure isolation per batch：单批失败继续下一批；
     * DB 写回失败<b>传播</b>——完成标记不写，走 §6.2 次日重链接兜底）
     */
    private void embedInBatches(List<RagEntity> toEmbed, List<String> texts,
                                EmbeddingCapable embeddingClient) {
        int batchSize = properties.embeddingBatchSize();
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<float[]> embeddings;
            try {
                embeddings = embeddingClient.embedBatch(texts.subList(i, end), EmbeddingType.DOCUMENT);
            } catch (Exception e) {
                log.error("Failed to embed entity batch [{}-{}]: {}", i, end, e);
                continue;
            }
            updateEmbeddings(toEmbed.subList(i, end), embeddings);
        }
    }

    /**
     * 批量回写 embedding（过滤空结果；advisory 短事务 + 按 entityId 升序，§3.2.1 防线二）
     */
    private void updateEmbeddings(List<RagEntity> batchEntities, List<float[]> embeddings) {
        List<EntityMapper.EmbeddingUpdate> updates = new ArrayList<>(batchEntities.size());
        for (int j = 0; j < batchEntities.size() && j < embeddings.size(); j++) {
            float[] embedding = embeddings.get(j);
            if (embedding != null && embedding.length > 0) {
                updates.add(new EntityMapper.EmbeddingUpdate(batchEntities.get(j).getId(), embedding));
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        updates.sort(Comparator.comparingLong(EntityMapper.EmbeddingUpdate::id));
        // 单文档批次内实体同 scope（经 aggregateAndUpsert 构造性保证）
        Long userId = batchEntities.get(0).getUserId();
        Long teamId = batchEntities.get(0).getTeamId();
        lockRetryExecutor.execute(() ->
                transactionTemplate.executeWithoutResult(status ->
                        scopeLockTemplate.withinScopeLock(userId, teamId, () ->
                                entityMapper.updateEmbeddingBatch(updates))));
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
    private ChatCapable getChatClientSafe() {
        try {
            return llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class);
        } catch (Exception e) {
            log.warn("No CHAT client available for description compression: {}", e.getMessage());
            return null;
        }
    }
}
