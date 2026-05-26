package com.smart.rag.rag.agent.dto;

import java.util.List;

/**
 * 中间答案 — 子问题的阶段性回答（DeepRAG 启发）
 *
 * @param subQueryIndex 关联的子问题索引
 * @param subQuery      子问题原文
 * @param answer        中间答案
 * @param source        来源："retrieval"（基于检索结果）或 "parametric"（基于自身知识）
 * @param citedDocIds   引用的文档 ID（parametric 时为空）
 */
public record IntermediateAnswer(
    int subQueryIndex,
    String subQuery,
    String answer,
    String source,
    List<String> citedDocIds
) {}
