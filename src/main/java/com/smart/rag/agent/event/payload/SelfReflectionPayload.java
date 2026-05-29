package com.smart.rag.agent.event.payload;

/**
 * SELF_REFLECTION 事件 payload
 * <p>
 * 记录自省评估结果，用于判断是否需要继续检索或调整策略。
 *
 * @param relevanceScore    相关性评分 (0.0 ~ 1.0)
 * @param completenessScore 完整性评分 (0.0 ~ 1.0)
 * @param suggestion        自省建议（如 "need_more_retrieval", "sufficient"）
 */
public record SelfReflectionPayload(
    double relevanceScore,
    double completenessScore,
    String suggestion
) {}
