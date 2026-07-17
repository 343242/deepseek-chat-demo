package com.smart.rag.mode;

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

    /**
     * 阻塞式执行 — 策略负责链构建 + spec 创建 + 调用执行。
     * 返回 StrategyExecuteResult，由 ChatServiceImpl 统一后续处理。
     */
    default StrategyExecuteResult execute(StrategyExecutionContext ctx) {
        throw new UnsupportedOperationException(
            getMode() + " mode does not implement execute()");
    }

    /**
     * 流式执行 — 策略负责链构建 + 流式调用 + 流式收尾。
     * <p>
     * 返回 {@link StreamResult}：content Flux 走 advisor 链（Redis 记忆 load/save +
     * RagContextAdvisor 动态尾注入），references 为检索引用映射（非 RAG 时 null）。
     * content Flux 由 chatStream 经 fallbackExecutor 做跨模型降级；
     * references 由 chatStream 用 AtomicReference 捕获最终成功模型的值。
     */
    default StreamResult executeStream(StrategyExecutionContext ctx) {
        throw new UnsupportedOperationException(
            getMode() + " mode does not support streaming in this version.");
    }
}
