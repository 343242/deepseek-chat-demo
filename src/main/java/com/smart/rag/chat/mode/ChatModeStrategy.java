package com.smart.rag.chat.mode;

import com.smart.rag.chat.service.AdvisorChainContext;
import com.smart.rag.chat.service.ModeChainResult;
import com.smart.rag.chat.service.StrategyExecuteResult;
import com.smart.rag.chat.service.StrategyExecutionContext;
import com.smart.rag.exception.BusinessException;
import com.smart.rag.common.errorcode.ErrorCode;
import reactor.core.publisher.Flux;

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
        throw new BusinessException(ErrorCode.UNSUPPORTED_OPERATION,
            getMode() + " mode does not implement execute()");
    }

    /**
     * 流式执行 — 策略负责链构建 + 流式调用 + 流式收尾。
     * 内部使用 .stream().chatResponse() 追踪 lastAiResponse，
     * doFinally 中完成消息持久化。
     */
    default Flux<String> executeStream(StrategyExecutionContext ctx) {
        throw new BusinessException(ErrorCode.UNSUPPORTED_OPERATION,
            getMode() + " mode does not support streaming in this version.");
    }
}
