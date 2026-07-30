package com.smart.rag.rag.retrieval.entity;

import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.mapper.EntityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PC4a：frontier → 投票回链 chunks（§6.3 UnWeaver approval election）。
 * <p>
 * SRP：仅负责"frontier entities → chunk 列表"，不含融合/扩展。
 * chunk_score = max(frontier.composite_score)（默认 max 策略），votedByEntities 用于 trace。
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.entity", name = "enabled", havingValue = "true")
public class EntityVoteRetriever {

    private static final Logger log = LoggerFactory.getLogger(EntityVoteRetriever.class);

    private final EntityMapper entityMapper;
    private final RagEntityProperties properties;

    public EntityVoteRetriever(EntityMapper entityMapper, RagEntityProperties properties) {
        this.entityMapper = entityMapper;
        this.properties = properties;
    }

    /**
     * 投票回链 chunks。
     *
     * @param frontier frontier ScoredEntity 列表（携带 composite_score + name_display）
     * @param userId   用户 ID（chunk 回链 metadata->>'userId' 安全隔离）
     * @return 投票选出的 VotedChunk 列表（按 chunk_score 降序），可能为空
     */
    public List<VotedChunk> retrieve(List<ScoredEntity> frontier, long userId) {
        if (frontier == null || frontier.isEmpty()) {
            return List.of();
        }

        List<VotedChunk> chunks = entityMapper.voteBacklinkChunks(
                frontier, properties.chunkTopK(), String.valueOf(userId));

        log.info("Vote backlink: {} frontier entities → {} chunks", frontier.size(), chunks.size());
        return chunks;
    }
}
