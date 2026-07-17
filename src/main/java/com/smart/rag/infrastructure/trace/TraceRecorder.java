package com.smart.rag.infrastructure.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 链路追踪事件异步写入服务。
 * <p>
 * 设计要点（仿 {@code AdminAuditAsyncWriter}）：
 * <ul>
 *   <li>单线程 + 有界队列（2000）+ {@link ThreadPoolExecutor.CallerRunsPolicy}：队列满时让业务线程同步写入，
 *       <b>不丢数据</b>。</li>
 *   <li>写入失败只记日志，不影响主流程。</li>
 *   <li>documents 参数接收 {@code List<Map<String,Object>>}（仅元数据），内部序列化为 JSONB 字符串。</li>
 * </ul>
 * <p>
 * 由 {@link TraceAspect} 调用，业务代码零感知。
 */
@Component
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);
    private static final int QUEUE_CAPACITY = 2000;
    private static final int SUMMARY_MAX_LEN = 1000;

    private final TraceMapper mapper;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public TraceRecorder(TraceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.executor = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "trace-writer");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 异步记录一条链路事件。
     *
     * @param traceId       日志 traceId（可空）
     * @param sessionId     会话标识（conversationId）
     * @param userId        用户 ID
     * @param stepType      步骤类型（见 {@code TraceEvent} javadoc）
     * @param toolName      工具/服务名（可空）
     * @param success       是否成功
     * @param durationMs    耗时 ms（可空）
     * @param inputSummary  输入摘要（可空，自动截断 1000 字）
     * @param outputSummary 输出摘要（可空，自动截断 1000 字）
     * @param docCount      文档数（可空）
     * @param topScore      最高得分（可空）
     * @param documents     文档明细（List of metadata map，可空；内部序列化为 JSONB）
     */
    public void record(@Nullable String traceId, String sessionId, Long userId, String stepType,
                       @Nullable String toolName, boolean success, @Nullable Long durationMs,
                       @Nullable String inputSummary, @Nullable String outputSummary,
                       @Nullable Integer docCount, @Nullable Double topScore,
                       @Nullable List<Map<String, Object>> documents) {
        String documentsJson = serializeDocuments(documents);
        TraceEvent event = new TraceEvent(
                traceId,
                sessionId,
                userId,
                stepType,
                toolName,
                success,
                durationMs,
                truncate(inputSummary),
                truncate(outputSummary),
                docCount,
                topScore,
                documentsJson,
                Instant.now()
        );
        executor.submit(() -> {
            try {
                mapper.insert(event);
            } catch (Exception e) {
                log.error("trace_event insert failed: session={} step={} user={}",
                        sessionId, stepType, userId, e);
            }
        });
    }

    private @Nullable String serializeDocuments(@Nullable List<Map<String, Object>> documents) {
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(documents);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize trace documents, storing docCount only", e);
            return null;
        }
    }

    private @Nullable String truncate(@Nullable String s) {
        if (s == null) {
            return null;
        }
        return s.length() > SUMMARY_MAX_LEN ? s.substring(0, SUMMARY_MAX_LEN) + "..." : s;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
