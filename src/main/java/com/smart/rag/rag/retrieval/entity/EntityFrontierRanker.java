package com.smart.rag.rag.retrieval.entity;

import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.mapper.EntityMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PC2-PC3：seed entities → 向量匹配 → 融合排序 → frontier 剪枝（§6.2）。
 * <p>
 * SRP：仅负责"seed 名称 → frontier ScoredEntity 列表"，不含 LLM 抽取/投票/扩展。
 * 归一化与 composite_score 在 SQL 内完成（§6.2 window-max），本类仅传参 + 消融开关门控。
 * <p>
 * 消融开关（§12.1）：{@code weakTieEnabled=false} 时 γ=0；{@code communityDetectionEnabled=false} 时 β=0。
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class EntityFrontierRanker {

    private static final Logger log = LoggerFactory.getLogger(EntityFrontierRanker.class);

    private final EntityMapper entityMapper;
    private final LlmClientRegistry llmClientRegistry;
    private final RagEntityProperties properties;

    public EntityFrontierRanker(EntityMapper entityMapper,
                                LlmClientRegistry llmClientRegistry,
                                RagEntityProperties properties) {
        this.entityMapper = entityMapper;
        this.llmClientRegistry = llmClientRegistry;
        this.properties = properties;
    }

    /**
     * 将 seed entities 融合排序为 frontier。
     *
     * @param seedEntities seed 实体名列表（来自 EntitySeedExtractor）
     * @param userId       用户作用域
     * @param teamId       团队作用域（可为 null）
     * @return frontier ScoredEntity 列表（已按 composite_score 排序、剪枝），可能为空
     */
    public List<ScoredEntity> rank(List<String> seedEntities, long userId, @Nullable Long teamId) {
        if (seedEntities == null || seedEntities.isEmpty()) {
            return List.of();
        }

        // PC2：每个 seed entity 独立 embed（QUERY 类型），取并集作为匹配输入
        EmbeddingCapable embeddingClient = llmClientRegistry.getDefault(
                LlmCapability.EMBEDDING, EmbeddingCapable.class);
        List<float[]> seedEmbeddings = embeddingClient.embedBatch(seedEntities, EmbeddingType.QUERY);

        if (seedEmbeddings.isEmpty()) {
            log.debug("No seed embeddings produced for {} entities", seedEntities.size());
            return List.of();
        }

        // PC3：融合排序 + frontier 剪枝（SQL 内完成归一化与 composite_score 计算）
        double effectiveAlpha = properties.alpha();
        double effectiveBeta = properties.communityDetectionEnabled() ? properties.beta() : 0.0;
        double effectiveGamma = properties.weakTieEnabled() ? properties.gamma() : 0.0;

        List<ScoredEntity> frontier = entityMapper.findFrontierEntities(
                seedEmbeddings,
                properties.matchThreshold(),
                userId,
                teamId,
                properties.frontierBudget(),
                effectiveAlpha,
                effectiveBeta,
                effectiveGamma);

        log.info("Frontier ranking: {} seeds → {} matched entities (budget={})",
                seedEmbeddings.size(), frontier.size(), properties.frontierBudget());
        return frontier;
    }
}
