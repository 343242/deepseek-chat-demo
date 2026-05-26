package com.smart.rag.rag.agent.dto;

import java.util.List;

/**
 * 自省结果 — 检索后 LLM 的自我评估（Self-RAG 启发）
 *
 * @param subQueryIndex  关联的子问题索引
 * @param isRelevant     检索结果是否相关
 * @param isSufficient   信息是否足够回答
 * @param missingAspects 缺少的方面
 * @param nextAction     下一步：proceed / rewrite_and_search / rerank / switch_tool
 */
public record SelfReflection(
    int subQueryIndex,
    boolean isRelevant,
    boolean isSufficient,
    List<String> missingAspects,
    String nextAction
) {}
