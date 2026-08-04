package com.smart.rag.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smart.rag.infrastructure.trace.TraceEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * trace_event 脱敏 VO（管理员只读）。
 * <p>
 * {@link #documents} 在库内存储为 JSONB 字符串（可能含 chunk 正文 content），
 * 此 VO 已在序列化前剥离 {@code content} 字段——管理员只看到 docId/chunkId/fileName/score
 * 等元数据，不泄露文档正文。需要查看正文时走文档管理接口。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceEventVO(
    Long id,
    String traceId,
    String sessionId,
    Long userId,
    String stepType,
    String toolName,
    boolean success,
    Long durationMs,
    String inputSummary,
    String outputSummary,
    Integer docCount,
    Double topScore,
    /** 文档明细（已剥离 content，仅保留 id/元数据） */
    List<Map<String, Object>> documents,
    Instant createdAt
) {

    /**
     * 从实体构造，{@code documents} 传已脱敏的列表（content 已剥离）。
     */
    public static TraceEventVO of(TraceEvent e, List<Map<String, Object>> sanitizedDocs) {
        return new TraceEventVO(
            e.getId(), e.getTraceId(), e.getSessionId(), e.getUserId(),
            e.getStepType(), e.getToolName(), e.isSuccess(), e.getDurationMs(),
            e.getInputSummary(), e.getOutputSummary(),
            e.getDocCount(), e.getTopScore(),
            sanitizedDocs, e.getCreatedAt()
        );
    }
}
