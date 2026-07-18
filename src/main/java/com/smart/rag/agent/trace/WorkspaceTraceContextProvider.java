package com.smart.rag.agent.trace;

import com.smart.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.infrastructure.trace.TraceContextProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Agent 路径的追踪上下文提取实现（识别 {@link ToolWorkspace}）。
 * <p>
 * 归属于 agent 模块：ToolWorkspace 是 agent 私有类型，由本类直接引用并实现
 * {@link TraceContextProvider}，注入到 infrastructure 的 TraceAspect（DIP）。
 */
@Component
public class WorkspaceTraceContextProvider implements TraceContextProvider {

    @Override
    public boolean supports(@Nullable Object arg) {
        return arg instanceof ToolWorkspace;
    }

    @Override
    public @Nullable String extractSessionId(Object arg) {
        return ((ToolWorkspace) arg).getSessionId();
    }

    @Override
    public @Nullable Long extractUserId(Object arg) {
        return ((ToolWorkspace) arg).getUserId();
    }
}
