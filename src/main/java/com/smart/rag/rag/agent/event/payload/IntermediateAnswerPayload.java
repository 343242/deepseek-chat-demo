package com.smart.rag.rag.agent.event.payload;

import java.util.List;

/**
 * INTERMEDIATE_ANSWER 事件 payload
 * <p>
 * 记录子问题的中间答案，用于多轮检索中的答案聚合和会话恢复。
 *
 * @param source       答案来源（如 "retrieval", "tool:xxx"）
 * @param subQuery     子问题文本
 * @param answerHash   答案内容的哈希值（脱敏）
 * @param citedDocIds  引用的文档 ID 列表
 */
public record IntermediateAnswerPayload(
    String source,
    String subQuery,
    String answerHash,
    List<String> citedDocIds
) {}
