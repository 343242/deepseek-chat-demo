package com.smart.rag.rag.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.concurrent.ExecutorMode;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.concurrent.TaskState;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.entity.RagEntity;
import com.smart.rag.rag.entity.RagEvent;
import com.smart.rag.rag.event.EtlVectorizedEvent;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 实体抽取编排服务（SRP: 仅编排，不含规范化/embedding 逻辑）
 * <p>
 * 监听 ETL 向量化完成事件（{@link EtlVectorizedEvent}），调度：
 * <ol>
 *   <li>从 vector_store 查文档所有 chunk</li>
 *   <li>并行 LLM 抽取（per chunk，failure-isolated）</li>
 *   <li>委托 EntityCanonicalizationService 规范化 + UPSERT + 增量共现维护（V30 §4）</li>
 *   <li>写 rag_event</li>
 *   <li>委托 EntityEmbeddingService embedding（不受 graphChanged 门控，§6.1）</li>
 *   <li>graphChanged 时经 DeriveDebouncer 触发结构分 derive（V30 §6.1，防抖合并）</li>
 *   <li>所有非异常退出路径写 entity_extracted_at 完成标记（V30 §6.2）</li>
 * </ol>
 * <p>
 * 仅响应 {@link EtlVectorizedEvent}（chunks 就绪信号），不监听 {@code EtlCompletedEvent}，
 * 避免 FastTrack 路径上对未分块 BM25 行的重复抽取。
 */
@Service
public class EntityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractionService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * §4.2 VERBATIM 抽取 prompt
     */
    private static final String EXTRACTION_SYSTEM_PROMPT = """
            你是一个信息抽取专家。阅读以下文本片段，提取一个完整事件和若干索引实体。

            ## 输出格式（JSON）
            {
              "event": "用一句话概括这段文本的核心事件/事实（保留完整语义，不分拆为三元组）",
              "entities": [
                {
                  "name": "实体规范名称",
                  "description": "该实体在这段文本中的描述（1-2 句话）",
                  "type": "person|organization|group|location|time|topic|work|action|product|metric|label"
                }
              ]
            }

            ## 规则
            1. event 必须是完整语义单元，不要拆成多个三元组
            2. entities 是索引点，不含完整语义——只抽取对检索有意义的实体
            3. 每个实体必须能独立理解，description 不要依赖上下文
            4. 实体类型覆盖: 时间、地点、人物、组织、群体、主题、作品、产品、动作、指标、标签（SAG 的 11 类）
            """;

    private final EntityCanonicalizationService canonicalizationService;
    private final EntityEmbeddingService embeddingService;
    private final EntityMapper entityMapper;
    private final EventMapper eventMapper;
    private final RagDocumentMapper documentMapper;
    private final VectorStoreMapper vectorStoreMapper;
    private final LlmClientRegistry llmClientRegistry;
    private final ExecutorService etlCpuExecutor;
    private final DeriveDebouncer deriveDebouncer;
    private final ScopedTasks scopedTasks;

    public EntityExtractionService(EntityCanonicalizationService canonicalizationService,
                                    EntityEmbeddingService embeddingService,
                                    EntityMapper entityMapper,
                                    EventMapper eventMapper,
                                    RagDocumentMapper documentMapper,
                                    VectorStoreMapper vectorStoreMapper,
                                    LlmClientRegistry llmClientRegistry,
                                    ExecutorService etlCpuExecutor,
                                    DeriveDebouncer deriveDebouncer,
                                    ScopedTasks scopedTasks) {
        this.canonicalizationService = canonicalizationService;
        this.embeddingService = embeddingService;
        this.entityMapper = entityMapper;
        this.eventMapper = eventMapper;
        this.documentMapper = documentMapper;
        this.vectorStoreMapper = vectorStoreMapper;
        this.llmClientRegistry = llmClientRegistry;
        this.etlCpuExecutor = etlCpuExecutor;
        this.deriveDebouncer = deriveDebouncer;
        this.scopedTasks = scopedTasks;
    }


    @EventListener
    @Async("etlIoExecutor")
    public void onEtlVectorized(EtlVectorizedEvent event) {
        extractAndIndex(event.documentId(), event.userId(), event.teamId());
    }

    /**
     * 编排实体抽取全流程。
     * <p>
     * 所有非异常退出路径（成功 / 无 chunk / 无实体）写 {@code entity_extracted_at} 完成标记；
     * 异常退出不标记——留待对账重链接检测次日重抽（幂等，V30 §6.2）。
     */
    public void extractAndIndex(Long documentId, Long userId, @Nullable Long teamId) {
        log.info("Starting entity extraction for documentId={}, userId={}, teamId={}",
                documentId, userId, teamId);
        try {
            // Step 1: 从 vector_store 查文档所有 chunk
            List<VectorStoreMapper.VectorStoreRow> chunkRows = vectorStoreMapper.selectChunksByDocumentId(
                    String.valueOf(documentId));
            if (chunkRows.isEmpty()) {
                log.info("No chunks found for documentId={}, skipping entity extraction", documentId);
                markEntityExtracted(documentId);
                return;
            }
            log.info("Found {} chunks for documentId={}", chunkRows.size(), documentId);

            // Step 2: 并行 LLM 抽取（per chunk，failure-isolated）
            List<EntityCanonicalizationService.ParsedExtraction> extractions =
                    extractChunksConcurrently(chunkRows);
            if (extractions.isEmpty()) {
                log.info("No entities extracted from any chunk for documentId={}", documentId);
                markEntityExtracted(documentId);
                return;
            }

            // Step 3: 委托规范化 + UPSERT + 增量共现维护（V30 §4，RETURNING 驱动）
            EntityCanonicalizationService.AggregateResult result = canonicalizationService.aggregateAndUpsert(
                    extractions, userId, teamId, documentId);

            // Step 4: 写 rag_event（per chunk，自动提交单行——白名单安全，§3.2.1）
            insertEvents(extractions, documentId, userId, teamId);

            // Step 5: embedding（不受 graphChanged 门控：保留对实体描述更新的自愈覆盖，§6.1）
            if (!result.entityIds().isEmpty()) {
                embedEntities(result.entityIds());
            }

            // Step 6: 结构分 derive 门控（§6.1：纯重投递 graphChanged=false 跳过 O(scope) 计算；
            // 防抖合并批量场景的 derive 次数，§3.6）
            if (result.graphChanged()) {
                deriveDebouncer.submit(userId, teamId);
            }

            markEntityExtracted(documentId);
            log.info("Entity extraction completed for documentId={}: {} entities, {} extractions, graphChanged={}",
                    documentId, result.entityIds().size(), extractions.size(), result.graphChanged());

        } catch (Exception e) {
            // 整体失败不影响 Path A/B（§8.3 / AC7）；完成标记不写 → §6.2 次日重链接兜底（V30）
            log.error("Entity extraction failed for documentId={}: {}", documentId, e.getMessage(), e);
        }
    }

    /**
     * Step 2: 结构化并发并行抽取所有 chunk（etlCpuExecutor 共享线程池）。
     * 单个 chunk 失败不影响其他 chunk（failure isolation per §8.3，extractChunk 内部兜底返回 null）。
     */
    private List<EntityCanonicalizationService.ParsedExtraction> extractChunksConcurrently(
            List<VectorStoreMapper.VectorStoreRow> chunkRows) {
        ChatCapable chatClient = llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class);
        List<EntityCanonicalizationService.ParsedExtraction> extractions = new ArrayList<>();

        ScopeOptions options = ScopeOptions.builder("entity-extract")
                .policy(ScopePolicy.COLLECT_ALL)
                .executorMode(ExecutorMode.SHARED_EXECUTOR)
                .executorOwnedByScope(false)
                .defaultTimeout(Duration.ofMinutes(5))
                .build();
        try (TaskScope scope = scopedTasks.open("entity-extract", options, etlCpuExecutor)) {
            for (VectorStoreMapper.VectorStoreRow row : chunkRows) {
                scope.fork("extract-chunk-" + row.id(),
                        () -> extractChunk(row.id(), row.content(), chatClient));
            }
            scope.join();
            for (Subtask<?> subtask : scope.subtasks()) {
                if (subtask.state() == TaskState.FAILED) {
                    log.warn("Chunk extraction failed, skipping (failure isolation)",
                            subtask.exception());
                }
                Object result = subtask.result();
                if (subtask.state() == TaskState.SUCCESS
                        && result instanceof EntityCanonicalizationService.ParsedExtraction ext
                        && !ext.entities().isEmpty()) {
                    extractions.add(ext);
                }
            }
        }
        return extractions;
    }

    /** Step 4: 写 rag_event（per chunk，仅非空 eventSummary） */
    private void insertEvents(List<EntityCanonicalizationService.ParsedExtraction> extractions,
                              Long documentId, Long userId, @Nullable Long teamId) {
        for (EntityCanonicalizationService.ParsedExtraction ext : extractions) {
            if (ext.eventSummary() != null && !ext.eventSummary().isEmpty()) {
                RagEvent event = new RagEvent();
                event.setChunkId(ext.chunkId());
                event.setSummary(ext.eventSummary());
                event.setUserId(userId);
                event.setTeamId(teamId);
                event.setDocumentId(documentId);
                eventMapper.insertIgnore(event);
            }
        }
    }

    /** Step 5: 委托 embedding（markCommunityStale 已并入写事务且受 graphChanged 门控，V30 §4.1 步骤 9） */
    private void embedEntities(List<Long> affectedEntityIds) {
        List<RagEntity> entitiesToEmbed = entityMapper.selectByIds(affectedEntityIds);
        embeddingService.embedEntities(entitiesToEmbed);
    }

    /** §6.2：抽取完成标记（自动提交单语句 UPDATE）。 */
    private void markEntityExtracted(Long documentId) {
        documentMapper.markEntityExtracted(documentId);
    }

    /**
     * 单个 chunk 的 LLM 抽取（failure-isolated）
     */
    private EntityCanonicalizationService.ParsedExtraction extractChunk(
            String chunkId, String content, ChatCapable chatClient) {
        try {
            ChatRequest request = ChatRequest.withSystem(EXTRACTION_SYSTEM_PROMPT, content);
            LlmResponse response = chatClient.chat(request);
            String text = response.content();

            if (text == null || text.isBlank()) {
                return null;
            }

            // Tolerate malformed JSON: strip markdown code fences if present
            String json = text.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            JsonNode root = OBJECT_MAPPER.readTree(json);
            String eventSummary = root.has("event") ? root.get("event").asText("") : "";
            return new EntityCanonicalizationService.ParsedExtraction(
                    chunkId, eventSummary, parseEntities(root));

        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM extraction JSON for chunk {}: {}", chunkId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("LLM extraction failed for chunk {}: {}", chunkId, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 LLM 输出 JSON 根节点的 entities 数组（过滤空 name 项）
     */
    private static List<EntityCanonicalizationService.ParsedEntity> parseEntities(JsonNode root) {
        List<EntityCanonicalizationService.ParsedEntity> entities = new ArrayList<>();
        if (root.has("entities") && root.get("entities").isArray()) {
            for (JsonNode entityNode : root.get("entities")) {
                String name = entityNode.has("name") ? entityNode.get("name").asText("") : "";
                String description = entityNode.has("description")
                        ? entityNode.get("description").asText("") : "";
                String type = entityNode.has("type") ? entityNode.get("type").asText("") : "";
                if (!name.isEmpty()) {
                    entities.add(new EntityCanonicalizationService.ParsedEntity(
                            name, description, type));
                }
            }
        }
        return entities;
    }
}
