package com.smart.rag.agent.event;

/**
 * Agent 会话事件类型枚举
 * <p>
 * 与数据库 agent_session_event.event_type 列（VARCHAR）对应，
 * 通过 {@link AgentEventTypeHandler} 进行 Java 枚举与数据库字符串的转换。
 */
public enum AgentEventType {

    /** 意图分类结果 */
    INTENT_CLASSIFIED,

    /** 子问题中间答案 */
    INTERMEDIATE_ANSWER,

    /** 自省结果 */
    SELF_REFLECTION,

    /** 检索策略变更 */
    RETRIEVAL_STRATEGY,

    /** Tool 调用记录 */
    TOOL_CALLED,

    /** 护栏触发 */
    GUARDRAIL_TRIGGERED
}
