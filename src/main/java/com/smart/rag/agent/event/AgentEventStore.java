package com.smart.rag.agent.event;

import com.smart.rag.agent.event.payload.GuardrailTriggeredPayload;
import com.smart.rag.agent.event.payload.IntentClassifiedPayload;
import com.smart.rag.agent.event.payload.IntermediateAnswerPayload;
import com.smart.rag.agent.event.payload.RetrievalStrategyPayload;
import com.smart.rag.agent.event.payload.SelfReflectionPayload;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    /** 异步写入队列容量（仿 TraceRecorder：队列满时 CallerRunsPolicy 同步回退，不丢数据）*/
    private static final int QUEUE_CAPACITY = 2000;

    private final AgentEventMapper mapper;
    private final EventPayloadMapper payloadMapper;
    private final ExecutorService executor;

    public AgentEventStore(AgentEventMapper mapper, EventPayloadMapper payloadMapper) {
        this.mapper = mapper;
        this.payloadMapper = payloadMapper;
        // 单线程 + 有界队列 + CallerRunsPolicy：写入不阻塞业务（含 reactive 流式线程），
        // 队列满时让调用方线程同步写，保证不丢事件。daemon 线程随 JVM 退出。
        this.executor = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "agent-event-writer");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /** 恢复快照最大加载事件条数 */
    private static final int MAX_SNAPSHOT_EVENTS = 200;

    // === 类型安全的 payload 记录方法 ===

    /**
     * 记录意图分类事件
     */
    public void recordIntentClassified(String sessionId, Long userId, IntentClassifiedPayload payload) {
        String data = payloadMapper.toJson(payload);
        record(sessionId, userId, AgentEventType.INTENT_CLASSIFIED, AgentEventPriority.CRITICAL,
            data, null, null, null);
    }

    /**
     * 记录中间答案事件
     */
    public void recordIntermediateAnswer(String sessionId, Long userId, IntermediateAnswerPayload payload) {
        String data = payloadMapper.toJson(payload);
        record(sessionId, userId, AgentEventType.INTERMEDIATE_ANSWER, AgentEventPriority.CRITICAL,
            data, null, null, null);
    }

    /**
     * 记录护栏触发事件
     */
    public void recordGuardrailTriggered(String sessionId, Long userId, GuardrailTriggeredPayload payload) {
        String data = payloadMapper.toJson(payload);
        record(sessionId, userId, AgentEventType.GUARDRAIL_TRIGGERED, AgentEventPriority.CRITICAL,
            data, null, null, null);
    }

    /**
     * 记录自省结果事件
     */
    public void recordSelfReflection(String sessionId, Long userId, SelfReflectionPayload payload) {
        String data = payloadMapper.toJson(payload);
        record(sessionId, userId, AgentEventType.SELF_REFLECTION, AgentEventPriority.HIGH,
            data, null, null, null);
    }

    /**
     * 记录检索策略变更事件
     */
    public void recordRetrievalStrategy(String sessionId, Long userId, RetrievalStrategyPayload payload) {
        String data = payloadMapper.toJson(payload);
        record(sessionId, userId, AgentEventType.RETRIEVAL_STRATEGY, AgentEventPriority.HIGH,
            data, null, null, null);
    }

    // === 向后兼容的便捷方法 ===

    /**
     * 记录事件
     *
     * @param sessionId  会话 ID
     * @param userId     用户 ID
     * @param eventType  事件类型
     * @param priority   优先级
     * @param data       事件数据 JSON
     * @param toolName   Tool 名称（可空）
     * @param success    是否成功（可空）
     * @param durationMs 耗时 ms（可空）
     */
    public void record(String sessionId, Long userId, AgentEventType eventType, AgentEventPriority priority,
                       String data, @Nullable String toolName, @Nullable Boolean success,
                       @Nullable Long durationMs) {
        AgentSessionEvent event = new AgentSessionEvent(
            sessionId, userId, eventType, priority, data,
            toolName, success, durationMs, Instant.now()
        );
        // 异步写入：不阻塞业务线程（含 reactive 流式线程）。
        // submit 失败（如 executor 已关闭）由队列满时的 CallerRunsPolicy 兜底同步执行。
        executor.submit(() -> {
            try {
                mapper.insert(event);
                log.debug("Recorded agent event: type={}, session={}, tool={}",
                    eventType, sessionId, toolName);
            } catch (Exception e) {
                // 事件记录失败不应影响主流程
                log.error("Failed to record agent event: type={}, session={}",
                    eventType, sessionId, e);
            }
        });
    }

    /**
     * 优雅关闭：排空待写事件（最多等待 5s），避免应用关闭时丢失已提交未写入的事件。
     */
    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 记录 Tool 调用事件（便捷方法）
     */
    public void recordToolCall(String sessionId, Long userId, String toolName,
                               boolean success, String data, long durationMs) {
        record(sessionId, userId, AgentEventType.TOOL_CALLED, AgentEventPriority.NORMAL,
            data, toolName, success, durationMs);
    }

    /**
     * 记录护栏触发事件（便捷方法，向后兼容）
     */
    public void recordGuardrail(String sessionId, Long userId, String reason, String data) {
        record(sessionId, userId, AgentEventType.GUARDRAIL_TRIGGERED, AgentEventPriority.CRITICAL,
            data, null, null, null);
    }

    /**
     * 构建恢复快照 -- 优先级分层恢复
     * <p>
     * 当 ChatMemory compaction 截断早期消息时，用此方法构建恢复快照注入到 System Prompt。
     * 限制最大加载条数，避免长会话一次性加载过多事件。
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param maxBytes  最大字节数预算
     * @return 恢复快照文本，供注入到 System Prompt
     */
    public String buildResumeSnapshot(String sessionId, Long userId, int maxBytes) {
        List<AgentSessionEvent> events = mapper.selectBySessionIdOrderByPriorityLimited(
            sessionId, userId, MAX_SNAPSHOT_EVENTS);

        if (events.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## 前序会话恢复\n\n");
        int budget = maxBytes;

        // P1: 意图分类 + 中间答案 + 护栏触发 -- 永远保留
        for (AgentSessionEvent e : filterByPriority(events, AgentEventPriority.CRITICAL)) {
            if (budget < 50) break;
            String line = formatEvent(e);
            sb.append(line).append("\n");
            budget -= line.length();
        }

        // P2: 自省结果 + 检索策略历史
        for (AgentSessionEvent e : filterByPriority(events, AgentEventPriority.HIGH)) {
            if (budget < 50) break;
            String line = formatEvent(e);
            sb.append(line).append("\n");
            budget -= line.length();
        }

        // P3: Tool 调用统计（一行摘要）
        if (budget > 50) {
            long toolCount = events.stream()
                .filter(e -> e.getEventType() == AgentEventType.TOOL_CALLED)
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

    private List<AgentSessionEvent> filterByPriority(List<AgentSessionEvent> events, AgentEventPriority priority) {
        return events.stream()
            .filter(e -> e.getPriority() == priority)
            .collect(Collectors.toList());
    }

    /**
     * 格式化事件为可读文本，用于恢复快照。
     * <p>
     * 优先使用 {@link EventPayloadMapper} 反序列化 payload 获得结构化信息；
     * 反序列化失败时 fallback 到原始字符串拼接，保持向后兼容。
     */
    private String formatEvent(AgentSessionEvent event) {
        return switch (event.getEventType()) {
            case INTENT_CLASSIFIED -> {
                IntentClassifiedPayload p = payloadMapper.toIntentClassified(event.getData());
                yield (p != null)
                    ? "- Intent: %s (confidence=%.2f)".formatted(p.intent(), p.confidence())
                    : "- Intent: " + event.getData();
            }
            case INTERMEDIATE_ANSWER -> {
                IntermediateAnswerPayload p = payloadMapper.toIntermediateAnswer(event.getData());
                yield (p != null)
                    ? "- Answer: source=%s, subQuery=%s, docs=%d".formatted(
                        p.source(), truncate(p.subQuery(), 40), p.citedDocIds().size())
                    : "- Answer: " + event.getData();
            }
            case GUARDRAIL_TRIGGERED -> {
                GuardrailTriggeredPayload p = payloadMapper.toGuardrailTriggered(event.getData());
                yield (p != null)
                    ? "- Guardrail: %s (%s) -> %s".formatted(p.guardrailName(), p.reason(), p.action())
                    : "- Guardrail: " + event.getData();
            }
            case SELF_REFLECTION -> {
                SelfReflectionPayload p = payloadMapper.toSelfReflection(event.getData());
                yield (p != null)
                    ? "- Reflection: relevance=%.2f, completeness=%.2f, suggestion=%s".formatted(
                        p.relevanceScore(), p.completenessScore(), p.suggestion())
                    : "- Reflection: " + event.getData();
            }
            case RETRIEVAL_STRATEGY -> {
                RetrievalStrategyPayload p = payloadMapper.toRetrievalStrategy(event.getData());
                yield (p != null)
                    ? "- Strategy: %s (round=%d, subQueries=%d)".formatted(
                        p.strategy(), p.targetRound(), p.subQueries().size())
                    : "- Strategy: " + event.getData();
            }
            case TOOL_CALLED ->
                "- Tool[" + event.getToolName() + "]: "
                    + (Boolean.TRUE.equals(event.getSuccess()) ? "ok" : "failed")
                    + " (" + event.getDurationMs() + "ms)";
        };
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "null";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
