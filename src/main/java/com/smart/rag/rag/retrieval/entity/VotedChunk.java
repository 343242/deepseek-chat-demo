package com.smart.rag.rag.retrieval.entity;

import java.util.UUID;

/**
 * 投票回链 chunk（§6.3 UnWeaver approval election）。
 * <p>
 * chunk_score = 关联 frontier 实体中最高 composite_score（max 策略，默认）。
 * votedByEntities 记录投票实体名（逗号分隔，可解释性 trace，§9.1）。
 * <p>
 * 字段与 {@code EntityMapper.xml#voteBacklinkChunks} resultMap 列名一一对应。
 */
public record VotedChunk(
        UUID chunkId,
        String content,
        String metadata,
        double chunkScore,
        String votedByEntities
) {}
