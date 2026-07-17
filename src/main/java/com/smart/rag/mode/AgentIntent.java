package com.smart.rag.mode;

/**
 * Agent 意图枚举
 * <p>
 * 意图识别结果，决定暴露给 LLM 的 Tool 子集。
 */
public enum AgentIntent {
    /** 直接回答 — 通用知识、闲聊、简单问答，不需要知识库 */
    DIRECT_ANSWER,
    /** 检索类 — 需要知识库但不需精排 */
    RETRIEVAL,
    /** 深度检索 — 需要多轮检索+精排+改写 */
    DEEP_RETRIEVAL,
    /** 通用工具 — 数学计算、日期查询、代码执行等 */
    GENERAL_TOOL
}
