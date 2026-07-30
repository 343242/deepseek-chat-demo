package com.smart.rag.rag.retrieval.entity;

import java.util.UUID;

/**
 * SAG 结构扩展发现的 chunk（§6.4 query-time expansion）。
 * <p>
 * chunk_score = δ × composite_score（发现它的中间 frontier 实体的结构传递分）。
 * discoveredViaEntities 记录扩展路径上的实体 ID（逗号分隔，trace 用）。
 * <p>
 * 字段与 {@code EntityMapper.xml#expandChunks} resultMap 列名一一对应。
 */
public record ExpandedChunk(
        UUID chunkId,
        String content,
        String metadata,
        double chunkScore,
        String discoveredViaEntities
) {}
