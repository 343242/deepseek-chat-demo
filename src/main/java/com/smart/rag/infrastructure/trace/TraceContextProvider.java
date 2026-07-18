package com.smart.rag.infrastructure.trace;

import org.jspecify.annotations.Nullable;

/**
 * 链路追踪上下文提取 SPI。
 * <p>
 * 定义在 infrastructure.trace，由持有业务类型知识的模块（agent / chat）实现并注入，
 * 让 {@link TraceAspect} 能从方法参数中提取 sessionId / userId，而无需 infrastructure
 * 反向依赖业务包（DIP）。
 * <p>
 * 参照 {@code UserPermissionProvider} 的同款 SPI 模式：接口在基础设施层，
 * 实现在业务层。替代 TraceAspect 早期用字符串常量 + 反射识别业务类型（ToolWorkspace /
 * StrategyExecutionContext）的脆弱做法——业务类重命名时字符串反射会静默失效，SPI 不会有此问题。
 */
public interface TraceContextProvider {

    /**
     * 判断本 provider 是否能从给定参数提取上下文（如参数是 ToolWorkspace / StrategyExecutionContext）。
     *
     * @param arg 方法参数（可能为 null）
     * @return true = 本 provider 能处理该参数类型
     */
    boolean supports(@Nullable Object arg);

    /**
     * 从参数提取 sessionId（如 ToolWorkspace.getSessionId / StrategyExecutionContext.conversationId）。
     * 无法提取时返回 null。
     */
    @Nullable String extractSessionId(Object arg);

    /**
     * 从参数提取 userId。
     * 无法提取时返回 null。
     */
    @Nullable Long extractUserId(Object arg);
}
