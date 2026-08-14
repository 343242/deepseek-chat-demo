package com.smart.rag.infrastructure.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注链路追踪需要记录的步骤方法（横切关注点，可服务于 RAG / Agent / Chat 任何链路）。
 * <p>
 * 由 {@link TraceAspect} 拦截，自动把步骤的输入摘要、输出摘要、召回文档、耗时、
 * 成功/失败等写入 {@code trace_event} 表，供事后排障与质量分析。
 * <p>
 * <b>使用约束</b>：
 * <ul>
 *   <li>仅可标注 Spring Bean 的 <b>public</b> 方法（Spring AOP 基于代理，private/protected 不可拦截）。</li>
 *   <li>被标注方法的参数中，应包含 {@code ToolWorkspace}（Agent 路径）或
 *       {@code StrategyExecutionContext}（Chat 路径），切面据此提取 sessionId/userId。
 *       若两者都没有，切面降级用 MDC {@code ragSessionId} / {@code ragUserId} + MDC {@code traceId} 兜底。</li>
 *   <li>切面从返回值提取信息：Agent 路径返回 {@code String}（ToolResult JSON），
 *       Chat 路径返回 {@code List<Document>} 或 {@code ChatRefResult}，切面按类型自动适配；
 *       无法识别的返回类型会尝试从参数中找 {@code List<Document>}。</li>
 * </ul>
 *
 * @see TraceAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TracedStep {

    /**
     * 步骤类型，写入 {@code trace_event.step_type}。
     * 约定值：QUERY_REWRITE / VECTOR_SEARCH / BM25_SEARCH / RRF_FUSION / PATH_RECALL / RERANK / CONTEXT_ASSEMBLY / HYBRID_SEARCH。
     * 不限定枚举——未来新增链路类型（如 AGENT_INTENT / TOOL_CALLED）可直接用新字符串，无需改注解定义。
     */
    String value();
}
