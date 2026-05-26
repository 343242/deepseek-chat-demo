package com.smart.rag.rag.agent.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 事件存储 -- PostgreSQL 事件存储（V1: PG-only）
 * <p>
 * 设计原则：
 * <ul>
 *   <li>V1 仅使用 PostgreSQL，避免 Redis 双写复杂度</li>
 *   <li>14 天 TTL 自动清理（应用层启动时或 pg_cron）</li>
 *   <li>与设计文档 AgentTrace 合并 -- 一份数据，两种用途</li>
 *   <li>后续迭代如需降低读取延迟，可引入 Redis 缓存层</li>
 * </ul>
 */
@Component
public class AgentEventStore {

    private static final Logger log = LoggerFactory.getLogger(AgentEventStore.class);

    private final AgentEventMapper mapper;

    public AgentEventStore(AgentEventMapper mapper) {
        this.mapper = mapper;
    }

    // === 事件类型常量 ===

    /** 意图分类结果 */
    public static final String EVENT_INTENT_CLASSIFIED = "INTENT_CLASSIFIED";
    /** 子问题中间答案 */
    public static final String EVENT_INTERMEDIATE_ANSWER = "INTERMEDIATE_ANSWER";
    /** 自省结果 */
    public static final String EVENT_SELF_REFLECTION = "SELF_REFLECTION";
    /** 检索策略变更 */
    public static final String EVENT_RETRIEVAL_STRATEGY = "RETRIEVAL_STRATEGY";
    /** Tool 调用记录 */
    public static final String EVENT_TOOL_CALLED = "TOOL_CALLED";
    /** 护栏触发 */
    public static final String EVENT_GUARDRAIL_TRIGGERED = "GUARDRAIL_TRIGGERED";

    // === 优先级常量 ===

    /** Critical -- 意图分类、中间答案、护栏触发 */
    public static final int PRIORITY_CRITICAL = 1;
    /** High -- 自省结果、检索策略变更 */
    public static final int PRIORITY_HIGH = 2;
    /** Normal -- Tool 调用记录 */
    public static final int PRIORITY_NORMAL = 3;

    /**
     * 记录事件
     *
     * @param sessionId  会话 ID
     * @param userId     用户 ID
     * @param eventType  事件类型
     * @param priority   优先级 (1=Critical, 2=High, 3=Normal)
     * @param data       事件数据 JSON
     * @param toolName   Tool 名称（可空）
     * @param success    是否成功（可空）
     * @param durationMs 耗时 ms（可空）
     */
    public void record(String sessionId, Long userId, String eventType, int priority,
                       String data, String toolName, Boolean success, Long durationMs) {
        try {
            AgentSessionEvent event = new AgentSessionEvent(
                sessionId, userId, eventType, priority, data,
                toolName, success, durationMs, Instant.now()
            );
            mapper.insert(event);
            log.debug("Recorded agent event: type={}, session={}, tool={}",
                eventType, sessionId, toolName);
        } catch (Exception e) {
            // 事件记录失败不应影响主流程
            log.error("Failed to record agent event: type={}, session={}",
                eventType, sessionId, e);
        }
    }

    /**
     * 记录 Tool 调用事件（便捷方法）
     */
    public void recordToolCall(String sessionId, Long userId, String toolName,
                               boolean success, String data, long durationMs) {
        record(sessionId, userId, EVENT_TOOL_CALLED, PRIORITY_NORMAL,
            data, toolName, success, durationMs);
    }

    /**
     * 记录护栏触发事件
     */
    public void recordGuardrail(String sessionId, Long userId, String reason, String data) {
        record(sessionId, userId, EVENT_GUARDRAIL_TRIGGERED, PRIORITY_CRITICAL,
            data, null, null, null);
    }

    /**
     * 构建恢复快照 -- 优先级分层恢复
     * <p>
     * 当 ChatMemory compaction 截断早期消息时，用此方法构建恢复快照注入到 System Prompt。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param maxBytes  最大字节数预算
     * @return 恢复快照文本，供注入到 System Prompt
     */
    public String buildResumeSnapshot(String sessionId, Long userId, int maxBytes) {
        List<AgentSessionEvent> events = mapper.selectBySessionIdOrderByPriority(sessionId, userId);

        if (events.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## 前序会话恢复\n\n");
        int budget = maxBytes;

        // P1: 意图分类 + 中间答案 + 护栏触发 -- 永远保留
        for (AgentSessionEvent e : filterByPriority(events, PRIORITY_CRITICAL)) {
            if (budget < 50) break;
            String line = formatEvent(e);
            sb.append(line).append("\n");
            budget -= line.length();
        }

        // P2: 自省结果 + 检索策略历史
        for (AgentSessionEvent e : filterByPriority(events, PRIORITY_HIGH)) {
            if (budget < 50) break;
            String line = formatEvent(e);
            sb.append(line).append("\n");
            budget -= line.length();
        }

        // P3: Tool 调用统计（一行摘要）
        if (budget > 50) {
            long toolCount = events.stream()
                .filter(e -> EVENT_TOOL_CALLED.equals(e.getEventType()))
                .count();
            sb.append("Tools used: ").append(toolCount).append(" calls total\n");
        }

        // 末尾附加按需查询指令
        sb.append("\nFor full event history, use agentEventLookup tool.\n");

        return sb.toString();
    }

    /**
     * 搜索历史事件（含 userId 多租户隔离）
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param query     查询文本
     * @param limit     最大返回条数
     * @return 匹配的事件列表
     */
    public List<AgentSessionEvent> searchEvents(String sessionId, Long userId,
                                                 String query, int limit) {
        return mapper.searchBySessionAndUserAndQuery(sessionId, userId, query, limit);
    }

    // === 内部辅助 ===

    private List<AgentSessionEvent> filterByPriority(List<AgentSessionEvent> events, int priority) {
        return events.stream()
            .filter(e -> e.getPriority() == priority)
            .collect(Collectors.toList());
    }

    private String formatEvent(AgentSessionEvent event) {
        return switch (event.getEventType()) {
            case EVENT_INTENT_CLASSIFIED ->
                "- Intent: " + event.getData();
            case EVENT_INTERMEDIATE_ANSWER ->
                "- Answer: " + event.getData();
            case EVENT_GUARDRAIL_TRIGGERED ->
                "- Guardrail: " + event.getData();
            case EVENT_SELF_REFLECTION ->
                "- Reflection: " + event.getData();
            case EVENT_RETRIEVAL_STRATEGY ->
                "- Strategy: " + event.getData();
            case EVENT_TOOL_CALLED ->
                "- Tool[" + event.getToolName() + "]: "
                    + (Boolean.TRUE.equals(event.getSuccess()) ? "ok" : "failed")
                    + " (" + event.getDurationMs() + "ms)";
            default ->
                "- " + event.getEventType() + ": " + event.getData();
        };
    }
}
