package com.smart.rag.chat.mode;

/**
 * 对话模式路由策略接口
 * <p>
 * 每种 ChatMode 对应一个 ChatModeStrategy 实现，
 * 负责决定该模式下是否需要记忆、上下文注入、思考输出等能力。
 * <p>
 * ModeRouter 根据 ChatRequest 中的 mode 字段路由到对应策略实现。
 */
public interface ChatModeStrategy {

    /**
     * 该策略对应的对话模式
     */
    ChatMode getMode();

    /**
     * 是否启用会话记忆（MessageChatMemoryAdvisor）
     */
    boolean isMemoryEnabled();

    /**
     * 是否启用对话上下文注入（ConversationContextAdvisor）
     */
    boolean isContextEnabled();

    /**
     * 是否启用思考过程输出（enableThinking）
     * <p>
     * 仅在 MULTI_TURN 模式下有意义，SIMPLE 模式恒返回 false。
     */
    boolean isThinkingEnabled();

    /**
     * 是否为 Agent 模式
     * <p>
     * Agent 模式下由 Agent 编排层接管 Tool 选择和 System Prompt 注入，
     * 跳过全局 ToolCallAdvisor、DB System Prompt、DB ModelParams。
     * <p>
     * default false 保证 SIMPLE / MULTI_TURN 无需改动。
     */
    default boolean isAgentMode() {
        return false;
    }
}
