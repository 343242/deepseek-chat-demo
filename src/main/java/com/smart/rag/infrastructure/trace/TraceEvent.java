package com.smart.rag.infrastructure.trace;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 链路追踪事件实体（横切关注点，可记录 RAG / Agent / Chat 任意链路的步骤明细）。
 * <p>
 * 对应表 {@code trace_event}，每个步骤一条记录，通过 {@code sessionId}（复用 conversationId）
 * 串联一次请求的完整链路。永久保留（不自动清理）。
 *
 * @see TraceRecorder 写入服务
 * @see TraceAspect AOP 切面（自动埋点）
 */
@TableName(value = "trace_event", autoResultMap = true)
public class TraceEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** micrometer 注入的日志 traceId；可空（无 tracing 环境时为 null） */
    @TableField("trace_id")
    private @Nullable String traceId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    /** 步骤类型：QUERY_REWRITE / VECTOR_SEARCH / BM25_SEARCH / RRF_FUSION / RERANK / CONTEXT_ASSEMBLY / HYBRID_SEARCH 等 */
    @TableField("step_type")
    private String stepType;

    @TableField("tool_name")
    private @Nullable String toolName;

    @TableField("success")
    private boolean success;

    @TableField("duration_ms")
    private @Nullable Long durationMs;

    /** 输入摘要（如改写前 query），已截断到 1000 字 */
    @TableField("input_summary")
    private @Nullable String inputSummary;

    /** 输出摘要（如改写后 query），已截断到 1000 字 */
    @TableField("output_summary")
    private @Nullable String outputSummary;

    @TableField("doc_count")
    private @Nullable Integer docCount;

    @TableField("top_score")
    private @Nullable Double topScore;

    /** 文档明细 JSONB（仅元数据，不含正文） */
    @TableField("documents")
    private @Nullable String documents;

    @TableField("created_at")
    private Instant createdAt;

    public TraceEvent() {
    }

    public TraceEvent(@Nullable String traceId, String sessionId, Long userId, String stepType,
                      @Nullable String toolName, boolean success, @Nullable Long durationMs,
                      @Nullable String inputSummary, @Nullable String outputSummary,
                      @Nullable Integer docCount, @Nullable Double topScore,
                      @Nullable String documents, Instant createdAt) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.stepType = stepType;
        this.toolName = toolName;
        this.success = success;
        this.durationMs = durationMs;
        this.inputSummary = inputSummary;
        this.outputSummary = outputSummary;
        this.docCount = docCount;
        this.topScore = topScore;
        this.documents = documents;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public @Nullable String getTraceId() { return traceId; }
    public void setTraceId(@Nullable String traceId) { this.traceId = traceId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }

    public @Nullable String getToolName() { return toolName; }
    public void setToolName(@Nullable String toolName) { this.toolName = toolName; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public @Nullable Long getDurationMs() { return durationMs; }
    public void setDurationMs(@Nullable Long durationMs) { this.durationMs = durationMs; }

    public @Nullable String getInputSummary() { return inputSummary; }
    public void setInputSummary(@Nullable String inputSummary) { this.inputSummary = inputSummary; }

    public @Nullable String getOutputSummary() { return outputSummary; }
    public void setOutputSummary(@Nullable String outputSummary) { this.outputSummary = outputSummary; }

    public @Nullable Integer getDocCount() { return docCount; }
    public void setDocCount(@Nullable Integer docCount) { this.docCount = docCount; }

    public @Nullable Double getTopScore() { return topScore; }
    public void setTopScore(@Nullable Double topScore) { this.topScore = topScore; }

    public @Nullable String getDocuments() { return documents; }
    public void setDocuments(@Nullable String documents) { this.documents = documents; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
