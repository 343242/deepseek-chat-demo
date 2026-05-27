package com.smart.rag.rag.agent.event.payload;

import java.util.List;

/**
 * RETRIEVAL_STRATEGY 事件 payload
 * <p>
 * 记录检索策略变更，用于会话恢复时了解已使用的检索策略。
 *
 * @param strategy    策略名称（如 "hybrid", "vector_only", "keyword_only"）
 * @param subQueries  生成的子问题列表
 * @param targetRound 目标轮次
 */
public record RetrievalStrategyPayload(
    String strategy,
    List<String> subQueries,
    int targetRound
) {}
