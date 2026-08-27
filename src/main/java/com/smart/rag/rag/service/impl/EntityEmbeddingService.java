package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.concurrent.ExecutorMode;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.concurrent.TaskState;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 实体 embedding 服务
 * <p>
 * 职责：聚合后 description 的批量 embed → 更新 rag_entity.embedding。
 * <p>
 * LLM 阶段（描述压缩、embed 批次）均为 IO 等待：虚拟线程 per-task 并行（不池化，JEP 444），
 * 经 {@code ScopeOptions.maxConcurrency} 限并发约束提供商配额；
 * DB 写回仍在调用线程串行——避免虚拟线程上 JDBC pinning，且 §3.2.1 的
 * advisory 短事务 + entityId 升序约束不变（全局升序排序后分块，天然保序）。
 * <p>
 * 写回重试耗尽 → 异常传播 → {@code extractAndIndex} 异常退出 → 完成标记不写 →
 * §6.2 次日重链接补（幂等；项目无补嵌调度，已核实 selectEntitiesNeedingEmbedding 无调用方）。
 */
@Service
public class EntityEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EntityEmbeddingService.class);

    private static final String COMPRESSION_SYSTEM_PROMPT =
            "请用 1-2 句话简洁概括以下文本的核心内容，保留关键信息，去除冗余。";

    /** 写回分批大小（advisory 短事务内单条多行 UPDATE；5 参 × 500 « 32767） */
    private static final int WRITE_BACK_BATCH_SIZE = 500;

    private final LlmClientRegistry llmClientRegistry;
    private final EntityChatClientResolver chatClientResolver;
    private final EntityMapper entityMapper;
    private final RagEntityProperties properties;
    private final ScopeLockTemplate scopeLockTemplate;
    private final LockRetryExecutor lockRetryExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ScopedTasks scopedTasks;

    public EntityEmbeddingService(com.smart.rag.infrastructure.llm.registry.LlmClientRegistry llmClientRegistry,
                                   EntityChatClientResolver chatClientResolver,
                                   EntityMapper entityMapper,
                                   RagEntityProperties properties,
                                   ScopeLockTemplate scopeLockTemplate,
                                   LockRetryExecutor lockRetryExecutor,
                                   TransactionTemplate transactionTemplate,
                                   ScopedTasks scopedTasks) {
        this.llmClientRegistry = llmClientRegistry;
        this.chatClientResolver = chatClientResolver;
        this.entityMapper = entityMapper;
        this.properties = properties;
        this.scopeLockTemplate = scopeLockTemplate;
        this.lockRetryExecutor = lockRetryExecutor;
        this.transactionTemplate = transactionTemplate;
        this.scopedTasks = scopedTasks;
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

        EmbeddingCapable embeddingClient = resolveEmbeddingClient();
        embedInBatches(toEmbed, texts, embeddingClient);

        log.info("Embedded {} entities (batchSize={})", toEmbed.size(), properties.embeddingBatchSize());
    }

    /**
     * 对超长 description 进行 LLM 压缩（虚拟线程并行，结果按原顺序回填；无 CHAT client 时保持原文）。
     *
     * @return 与 toEmbed 一一对应的待嵌入文本
     */
    private List<String> compressLongDescriptions(List<RagEntity> toEmbed) {
        int maxLen = properties.descriptionMaxLength();
        ChatCapable chatClient = getChatClientSafe();

        // 先算出文本，未超长/无 client 直接落位，只把真正要调 LLM 的槽位并行化
        String[] texts = new String[toEmbed.size()];
        List<Integer> pendingIdx = new ArrayList<>();
        for (int i = 0; i < toEmbed.size(); i++) {
            String text = toEmbed.get(i).getDescription();
            texts[i] = text;
            if (text.length() > maxLen && chatClient != null) {
                pendingIdx.add(i);
            }
        }
        if (pendingIdx.isEmpty()) {
            return List.of(texts);
        }

        ScopeOptions options = ScopeOptions.builder("entity-desc-compress")
                .policy(ScopePolicy.COLLECT_ALL)
                .executorMode(ExecutorMode.VIRTUAL_THREAD_PER_TASK)
                .maxConcurrency(properties.llmConcurrency())
                .defaultTimeout(Duration.ofMinutes(10))
                .build();
        try (TaskScope scope = scopedTasks.open("entity-desc-compress", options)) {
            for (Integer idx : pendingIdx) {
                int i = idx;
                String original = texts[i];
                scope.fork("compress-entity-" + i, () -> texts[i] = compressDescription(original, chatClient));
            }
            scope.join();
            for (Subtask<?> subtask : scope.subtasks()) {
                if (subtask.state() == TaskState.FAILED) {
                    log.warn("Description compression task failed (fallback to truncate)", subtask.exception());
                }
            }
        }
        // 失败/超时槽位回退截断原文（compressDescription 内部已兜底异常，此处防御 FAILED/null）
        for (int i : pendingIdx) {
            if (texts[i] == null) {
                texts[i] = truncate(toEmbed.get(i).getDescription(), maxLen);
            }
        }
        return List.of(texts);
    }

    /**
     * 分批 embed：LLM 调用并行（failure isolation per batch，单批失败继续）→ 全部完成后
     * 按 entityId 升序统一写回（写回失败<b>传播</b>——完成标记不写，走 §6.2 次日重链接兜底）
     */
    private void embedInBatches(List<RagEntity> toEmbed, List<String> texts,
                                EmbeddingCapable embeddingClient) {
        int batchSize = properties.embeddingBatchSize();
        int batchCount = (texts.size() + batchSize - 1) / batchSize;
        List<List<float[]>> embeddingsByBatch = new ArrayList<>(batchCount);
        for (int i = 0; i < batchCount; i++) {
            embeddingsByBatch.add(null);
        }

        ScopeOptions options = ScopeOptions.builder("entity-embed")
                .policy(ScopePolicy.COLLECT_ALL)
                .executorMode(ExecutorMode.VIRTUAL_THREAD_PER_TASK)
                .maxConcurrency(properties.llmConcurrency())
                .defaultTimeout(Duration.ofMinutes(10))
                .build();
        try (TaskScope scope = scopedTasks.open("entity-embed", options)) {
            for (int b = 0; b < batchCount; b++) {
                int batch = b;
                int start = b * batchSize;
                int end = Math.min(start + batchSize, texts.size());
                List<String> batchTexts = texts.subList(start, end);
                scope.fork("embed-batch-" + batch, () ->
                        embeddingsByBatch.set(batch, embeddingClient.embedBatch(batchTexts, EmbeddingType.DOCUMENT)));
            }
            scope.join();
            for (Subtask<?> subtask : scope.subtasks()) {
                if (subtask.state() == TaskState.FAILED) {
                    log.error("Failed to embed entity batch (skipped): {}", subtask.exception().toString());
                }
            }
        }

        // 写回：收集全部批次结果 → 全局按 entityId 升序 → 分块短事务（§3.2.1 防线二）
        List<EntityMapper.EmbeddingUpdate> updates = new ArrayList<>(toEmbed.size());
        for (int b = 0; b < batchCount; b++) {
            List<float[]> embeddings = embeddingsByBatch.get(b);
            if (embeddings == null) {
                continue;
            }
            int start = b * batchSize;
            List<RagEntity> batchEntities = toEmbed.subList(start, Math.min(start + batchSize, toEmbed.size()));
            for (int j = 0; j < batchEntities.size() && j < embeddings.size(); j++) {
                float[] embedding = embeddings.get(j);
                if (embedding != null && embedding.length > 0) {
                    updates.add(new EntityMapper.EmbeddingUpdate(batchEntities.get(j).getId(), embedding));
                }
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        updates.sort(Comparator.comparingLong(EntityMapper.EmbeddingUpdate::id));
        // 单文档批次内实体同 scope（经 aggregateAndUpsert 构造性保证）
        Long userId = toEmbed.get(0).getUserId();
        Long teamId = toEmbed.get(0).getTeamId();
        for (int i = 0; i < updates.size(); i += WRITE_BACK_BATCH_SIZE) {
            List<EntityMapper.EmbeddingUpdate> chunk =
                    updates.subList(i, Math.min(i + WRITE_BACK_BATCH_SIZE, updates.size()));
            lockRetryExecutor.execute(() ->
                    transactionTemplate.executeWithoutResult(status ->
                            scopeLockTemplate.withinScopeLock(userId, teamId, () ->
                                    entityMapper.updateEmbeddingBatch(chunk))));
        }
    }

    /** 截断回退（超长 description 压缩失败的兜底） */
    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
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
        return truncate(text, properties.descriptionMaxLength());
    }

    /**
     * 安全获取 EmbeddingCapable
     */
    private EmbeddingCapable resolveEmbeddingClient() {
        return llmClientRegistry.getDefault(LlmCapability.EMBEDDING, EmbeddingCapable.class);
    }

    /**
     * 安全获取 ChatCapable（用于 description 压缩；解析失败仅降级保留原文）
     */
    private ChatCapable getChatClientSafe() {
        try {
            return chatClientResolver.resolve();
        } catch (Exception e) {
            log.warn("No CHAT client available for description compression: {}", e.getMessage());
            return null;
        }
    }
}
