package com.smart.rag.chat.mode;

import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.ModeChainResult;

/**
 * 对话模式策略接口
 * <p>
 * 每种 ChatMode 对应一个 ChatModeStrategy 实现，
 * 负责决定该模式下 Advisor 链的组装方式。
 * <p>
 * ModeRouter 根据 ChatRequest 中的 mode 字段路由到对应策略实现。
 */
public interface ChatModeStrategy {

    /**
     * 该策略对应的对话模式
     */
    ChatMode getMode();

    /**
     * 构建该模式的 Advisor 链及模式相关元数据。
     * <p>
     * 每个策略自己决定链的组装方式、需要哪些 Advisor。
     * Agent 模式还会返回 intentResult、workspace、tokenCountingModel 等元数据。
     *
     * @param ctx 链构建上下文（含 conversationId、request、userId、cagContext、route）
     * @return 统一的 ModeChainResult（含 chain + 执行指示）
     */
    ModeChainResult buildAdvisorChain(AdvisorChainContext ctx);

    // === 以下 flag 方法标记 @Deprecated，Step 2 移除 ===
    // 保留是为了 ChatServiceImpl 中少量非链构建的 flag 依赖

    /**
     * @deprecated 由 buildAdvisorChain 内部决定，外部不再需要查询
     */
    @Deprecated
    boolean isMemoryEnabled();

    /**
     * @deprecated 由 buildAdvisorChain 内部决定，外部不再需要查询
     */
    @Deprecated
    default boolean isAgentMode() { return false; }

    // isThinkingEnabled() -- 未实现的功能，不属于本次重构范围，从接口移除
    // isContextEnabled() -- 由 buildAdvisorChain 内部决定，从接口移除
}
