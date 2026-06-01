package com.smart.rag.infrastructure.agent.trace;

import com.smart.rag.agent.intent.AgentIntent;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Agent 执行追踪记录
 * <p>
 * 每次请求一条，记录完整的 Agent 执行过程。
 * 请求级内存对象，汇总整次请求的统计信息，供日志和响应元数据使用。
 * <p>
 * 与 P2 优化 AgentSessionEvent 的关系：
 * <ul>
 *   <li>AgentSessionEvent（持久化到 PG）：记录每步事件的详细信息，供会话恢复</li>
 *   <li>AgentTrace（请求级内存对象）：汇总整次请求的统计信息，供日志和响应元数据</li>
 *   <li>ToolCallRecord 的字段映射到 AgentSessionEvent(event_type=TOOL_CALLED) 的 data JSONB</li>
 * </ul>
 *
 * @param traceId          追踪 ID（UUIDv7）
 * @param userId           用户 ID
 * @param query            原始查询
 * @param intent           意图分类结果
 * @param subQueries       子问题列表
 * @param toolCalls        Tool 调用记录
 * @param totalIterations  总迭代轮次
 * @param totalTokensUsed  总 token 消耗
 * @param totalDurationMs  总耗时（ms）
 * @param finalStatus      最终状态：COMPLETED / DEGRADED / FAILED / GUARDRAIL_STOPPED
 * @param stopReason       停止原因（正常完成 / 护栏触发 / 异常）
 */
public record AgentTrace(
    String traceId,
    long userId,
    String query,
    @Nullable AgentIntent intent,
    List<String> subQueries,
    List<ToolCallRecord> toolCalls,
    int totalIterations,
    long totalTokensUsed,
    long totalDurationMs,
    String finalStatus,
    @Nullable String stopReason
) {}
