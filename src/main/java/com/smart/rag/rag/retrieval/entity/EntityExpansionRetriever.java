package com.smart.rag.rag.retrieval.entity;

import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.mapper.EntityMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PC4b：frontier → SAG H 跳结构扩展 → chunks（§6.4 query-time expansion）。
 * <p>
 * SRP：仅负责"frontier → 结构扩展 chunk 列表"，不含融合/投票。
 * 纯结构 SQL JOIN（忠于 SAG §3.4：expansion relies solely on SQL joins），不加 query 语义过滤。
 * chunk_score = δ × composite_score（发现它的中间 frontier 实体的结构传递分）。
 * <p>
 * 禁用路径：{@code expansionHops <= 0} 时返回空列表，不执行 SQL（AC7）。
 */
@Component
public class EntityExpansionRetriever {

    private static final Logger log = LoggerFactory.getLogger(EntityExpansionRetriever.class);

    private final EntityMapper entityMapper;
    private final RagEntityProperties properties;

    public EntityExpansionRetriever(EntityMapper entityMapper, RagEntityProperties properties) {
        this.entityMapper = entityMapper;
        this.properties = properties;
    }

    /**
     * SAG 结构扩展。
     *
     * @param frontier frontier ScoredEntity 列表（携带 composite_score）
     * @param userId   用户 ID（结构隔离 + chunk 回链安全隔离）
     * @param teamId   团队作用域（可为 null）
     * @return 扩展发现的 ExpandedChunk 列表（按 chunk_score 降序），可能为空
     */
    public List<ExpandedChunk> retrieve(List<ScoredEntity> frontier, long userId, @Nullable Long teamId) {
        // AC7：expansionHops <= 0 时干净禁用，不执行 SQL
        if (frontier == null || frontier.isEmpty() || properties.expansionHops() <= 0) {
            return List.of();
        }

        List<ExpandedChunk> chunks = entityMapper.expandChunks(
                frontier,
                properties.expansionDecay(),
                properties.expandChunkTopK(),
                userId,
                teamId,
                String.valueOf(userId));

        log.info("SAG expansion: {} frontier entities → {} expanded chunks (hops={}, decay={})",
                frontier.size(), chunks.size(), properties.expansionHops(), properties.expansionDecay());
        return chunks;
    }
}
