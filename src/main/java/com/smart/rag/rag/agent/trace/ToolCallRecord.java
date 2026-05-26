package com.smart.rag.rag.agent.trace;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 单次 Tool 调用记录
 * <p>
 * 映射到 {@code AgentSessionEvent}(event_type=TOOL_CALLED) 的 data JSONB 字段。
 *
 * @param iteration      第几轮迭代
 * @param toolName       Tool 名称
 * @param inputParams    输入参数
 * @param success        是否成功
 * @param errorCategory  错误分类（可空）
 * @param resultDocCount 结果文档数
 * @param durationMs     耗时（ms）
 */
public record ToolCallRecord(
    int iteration,
    String toolName,
    Map<String, Object> inputParams,
    boolean success,
    @Nullable String errorCategory,
    int resultDocCount,
    long durationMs
) {}
