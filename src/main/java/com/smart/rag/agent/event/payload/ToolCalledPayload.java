package com.smart.rag.agent.event.payload;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * TOOL_CALLED 事件 payload
 * <p>
 * 记录单次 Tool 调用的详细信息，字段与 {@code ToolCallRecord} 对齐。
 * 作为 {@code TOOL_CALLED} 事件的正式结构化 payload。
 *
 * @param iteration      第几轮迭代
 * @param toolName       Tool 名称
 * @param inputParams    输入参数
 * @param success        是否成功
 * @param errorCategory  错误分类（可空）
 * @param resultDocCount 结果文档数
 * @param durationMs     耗时（ms）
 */
public record ToolCalledPayload(
    int iteration,
    String toolName,
    Map<String, Object> inputParams,
    boolean success,
    @Nullable String errorCategory,
    int resultDocCount,
    long durationMs
) {}
