package com.smart.rag.chat.trace;

import com.smart.rag.infrastructure.trace.TraceContextProvider;
import com.smart.rag.mode.StrategyExecutionContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Chat 路径的追踪上下文提取实现（识别 {@link StrategyExecutionContext}）。
 * <p>
 * 归属于 chat 模块：StrategyExecutionContext 是 chat 检索路径的执行上下文（mode 协议层定义），
 * chat 依赖 mode + infrastructure，由本类实现 {@link TraceContextProvider} 注入 TraceAspect（DIP）。
 * <p>
 * 不放在 mode 包：mode 是中立协议层，零依赖 infrastructure（见 mode 包纯净性约束），
 * 故由消费方 chat 实现。
 */
@Component
public class StrategyExecutionContextTraceProvider implements TraceContextProvider {

    @Override
    public boolean supports(@Nullable Object arg) {
        return arg instanceof StrategyExecutionContext;
    }

    @Override
    public String mode() {
        return MODE_CHAT;
    }

    @Override
    public @Nullable String extractSessionId(Object arg) {
        return ((StrategyExecutionContext) arg).conversationId();
    }

    @Override
    public @Nullable Long extractUserId(Object arg) {
        return ((StrategyExecutionContext) arg).userId();
    }
}
