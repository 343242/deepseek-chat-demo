package com.smart.rag.agent.event.payload;

/**
 * RETRIEVAL_STRATEGY 事件 payload。
 * <p>
 * 记录一次检索策略变更（工具切换），schema 对齐 AGENTIC-RAG-OPTIMIZATIONS.md §3.2：
 * <pre>{"from": "hybridSearch", "to": "vectorSearch", "reason": "..."}</pre>
 * <p>
 * 语义是"变更"而非"快照"——由 Self-RAG 自省输出 {@code nextAction=switch_tool} 时触发，
 * 记录从哪个检索工具切换到哪个，以及切换原因（来自自省的 missingAspects）。
 *
 * @param from    变更前的检索工具/策略名（如 "hybridSearch"）；首次检索时为 null
 * @param to      变更后的检索工具/策略名（如 "vectorSearch"）
 * @param reason  变更原因（来自自省的 missingAspects 拼接，或 LLM 给出的理由）
 */
public record RetrievalStrategyPayload(
    String from,
    String to,
    String reason
) {}
