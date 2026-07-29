package com.smart.rag.rag.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 实体抽取编排服务（SRP: 仅编排，不含规范化/embedding 逻辑）
 * <p>
 * 监听 ETL 向量化完成事件（{@link EtlVectorizedEvent}），调度：
 * <ol>
 *   <li>从 vector_store 查文档所有 chunk</li>
 *   <li>并行 LLM 抽取（per chunk，failure-isolated）</li>
 *   <li>委托 EntityCanonicalizationService 规范化 + UPSERT</li>
 *   <li>写 rag_event</li>
 *   <li>委托 EntityEmbeddingService embedding</li>
 *   <li>标记 community_stale=TRUE</li>
 * </ol>
 * <p>
 * 仅响应 {@link EtlVectorizedEvent}（chunks 就绪信号），不监听 {@code EtlCompletedEvent}，
 * 避免 FastTrack 路径上对未分块 BM25 行的重复抽取。
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
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
    private final VectorStoreMapper vectorStoreMapper;
    private final LlmClientRegistry llmClientRegistry;
    private final ExecutorService etlCpuExecutor;

    public EntityExtractionService(EntityCanonicalizationService canonicalizationService,
                                    EntityEmbeddingService embeddingService,
                                    EntityMapper entityMapper,
                                    EventMapper eventMapper,
                                    VectorStoreMapper vectorStoreMapper,
                                    LlmClientRegistry llmClientRegistry,
                                    ExecutorService etlCpuExecutor) {
        this.canonicalizationService = canonicalizationService;
        this.embeddingService = embeddingService;
        this.entityMapper = entityMapper;
        this.eventMapper = eventMapper;
        this.vectorStoreMapper = vectorStoreMapper;
        this.llmClientRegistry = llmClientRegistry;
        this.etlCpuExecutor = etlCpuExecutor;
    }


    @EventListener
    @Async("etlIoExecutor")
    public void onEtlVectorized(EtlVectorizedEvent event) {
        extractAndIndex(event.documentId(), event.userId(), event.teamId());
    }

    /**
     * 编排实体抽取全流程
     */
    public void extractAndIndex(Long documentId, Long userId, @Nullable Long teamId) {
        log.info("Starting entity extraction for documentId={}, userId={}, teamId={}",
                documentId, userId, teamId);
        try {
            // Step 1: 从 vector_store 查文档所有 chunk
            String docIdStr = String.valueOf(documentId);
            List<VectorStoreMapper.VectorStoreRow> chunkRows =
                    vectorStoreMapper.selectChunksByDocumentId(docIdStr);

            if (chunkRows.isEmpty()) {
                log.info("No chunks found for documentId={}, skipping entity extraction", documentId);
                return;
            }

            log.info("Found {} chunks for documentId={}", chunkRows.size(), documentId);

            // Step 2: 并行 LLM 抽取（per chunk，failure-isolated）
            ChatCapable chatClient = llmClientRegistry.getDefault(
                    LlmCapability.CHAT, ChatCapable.class);

            List<EntityCanonicalizationService.ParsedExtraction> extractions = new ArrayList<>();

            List<CompletableFuture<EntityCanonicalizationService.ParsedExtraction>> futures =
                    chunkRows.stream()
                            .map(row -> CompletableFuture.supplyAsync(
                                    () -> extractChunk(row.id(), row.content(), chatClient),
                                    etlCpuExecutor))
                            .toList();

            for (CompletableFuture<EntityCanonicalizationService.ParsedExtraction> future : futures) {
                try {
                    EntityCanonicalizationService.ParsedExtraction ext = future.join();
                    if (ext != null && !ext.entities().isEmpty()) {
                        extractions.add(ext);
                    }
                } catch (Exception e) {
                    // 单个 chunk 失败不影响其他 chunk（failure isolation per §8.3）
                    log.warn("Chunk extraction failed, skipping (failure isolation): {}", e.getMessage());
                }
            }

            if (extractions.isEmpty()) {
                log.info("No entities extracted from any chunk for documentId={}", documentId);
                return;
            }

            // Step 3: 委托规范化 + UPSERT
            List<Long> affectedEntityIds = canonicalizationService.aggregateAndUpsert(
                    extractions, userId, teamId);

            // Step 4: 写 rag_event（per chunk）
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

            // Step 5: 委托 embedding
            if (!affectedEntityIds.isEmpty()) {
                List<RagEntity> entitiesToEmbed = entityMapper.selectBatchIds(affectedEntityIds);
                embeddingService.embedEntities(entitiesToEmbed);

                // Step 6: 标记 community_stale=TRUE
                entityMapper.markCommunityStale(affectedEntityIds);
            }

            log.info("Entity extraction completed for documentId={}: {} entities, {} extractions",
                    documentId, affectedEntityIds.size(), extractions.size());

        } catch (Exception e) {
            // 整体失败不影响 Path A/B（§8.3 / AC7）
            log.error("Entity extraction failed for documentId={}: {}", documentId, e.getMessage(), e);
        }
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

            return new EntityCanonicalizationService.ParsedExtraction(chunkId, eventSummary, entities);

        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM extraction JSON for chunk {}: {}", chunkId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("LLM extraction failed for chunk {}: {}", chunkId, e.getMessage());
            return null;
        }
    }
}
