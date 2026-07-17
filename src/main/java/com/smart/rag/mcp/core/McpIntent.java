package com.smart.rag.mcp.core;

/**
 * MCP 工具路由意图（领域枚举）。
 * <p>
 * <b>core 自有枚举</b>——不可 import {@code com.smart.rag.mode.AgentIntent}
 * （ArchUnit 6.1：{@code core} 禁止 import {@code agent..}，见 design D-5）。值集与
 * {@code AgentIntent} 对齐，消费侧（future {@code AgentToolCallbackFactory}）做
 * {@code AgentIntent → McpIntent} 映射。yaml {@code mcp.policy.tools.<name>.intent} 值用本枚举名。
 */
public enum McpIntent {
    /** 直接回答——通用知识/闲聊，不需工具 */
    DIRECT_ANSWER,
    /** 检索类 */
    RETRIEVAL,
    /** 深度检索 */
    DEEP_RETRIEVAL,
    /** 通用工具 */
    GENERAL_TOOL
}
