package com.smart.rag.agent.event.payload;

/**
 * INTENT_CLASSIFIED 事件 payload
 * <p>
 * 记录意图分类结果，用于会话恢复时快速了解用户意图。
 *
 * @param intent        分类出的意图标识
 * @param confidence    分类置信度 (0.0 ~ 1.0)
 * @param rawQueryHash  原始查询的哈希值（脱敏）
 */
public record IntentClassifiedPayload(
    String intent,
    double confidence,
    String rawQueryHash
) {}
