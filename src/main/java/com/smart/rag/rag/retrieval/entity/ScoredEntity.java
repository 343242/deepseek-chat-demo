package com.smart.rag.rag.retrieval.entity;

/**
 * 融合排序后的 frontier 实体（§6.2）。
 * <p>
 * 由 {@code EntityMapper.findFrontierEntities()} 返回，携带原始三项分数、归一化值与
 * composite_score。归一化在 SQL 内用 window-max 完成（§6.2），Java 层不重复计算。
 * <p>
 * 字段与 {@code EntityMapper.xml#findFrontierEntities} resultMap 列名一一对应。
 */
public record ScoredEntity(
        long id,
        String nameDisplay,
        double queryRelevance,
        double bridge,
        double weakTie,
        int degree,
        double queryRelNorm,
        double bridgeNorm,
        double weakTieNorm,
        double compositeScore
) {}
