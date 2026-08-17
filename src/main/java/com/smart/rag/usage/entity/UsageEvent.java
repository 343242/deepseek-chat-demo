package com.smart.rag.usage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smart.rag.infrastructure.mybatis.UuidTypeHandler;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用量事件 — 一次 LLM 模型调用的完整记录（V28 {@code usage_event} 表）。
 * <p>
 * token 列为 {@code null} 表未知（厂商未返回 usage 且无估算依据），SUM 聚合自然忽略。
 */
@TableName(value = "usage_event", autoResultMap = true)
public class UsageEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件唯一 ID（UUID，消费幂等兜底：唯一约束冲突即重复投递）。类型必须为 java.util.UUID——
     *  列是 PG 原生 uuid，String 会被驱动按 varchar 发参，PG 不做隐式转换（V28 设计即如此）。 */
    @TableField(value = "event_id", typeHandler = UuidTypeHandler.class)
    private UUID eventId;

    @TableField("user_id")
    private Long userId;

    /** 调用场景（UsageScene 枚举名） */
    @TableField("scene")
    private String scene;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("model_id")
    private String modelId;

    @TableField("prompt_tokens")
    private Long promptTokens;

    @TableField("completion_tokens")
    private Long completionTokens;

    @TableField("total_tokens")
    private Long totalTokens;

    @TableField("estimated")
    private Boolean estimated;

    @TableField("success")
    private Boolean success;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    public UsageEvent() {
    }

    public UsageEvent(UUID eventId, Long userId, String scene, String conversationId,
                      String modelId, Long promptTokens, Long completionTokens, Long totalTokens,
                      boolean estimated, boolean success, long durationMs) {
        this.eventId = eventId;
        this.userId = userId;
        this.scene = scene;
        this.conversationId = conversationId;
        this.modelId = modelId;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.estimated = estimated;
        this.success = success;
        this.durationMs = durationMs;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public Long getUserId() { return userId; }
    public String getScene() { return scene; }
    public String getConversationId() { return conversationId; }
    public String getModelId() { return modelId; }
    public Long getPromptTokens() { return promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public Long getTotalTokens() { return totalTokens; }
    public Boolean getEstimated() { return estimated; }
    public Boolean getSuccess() { return success; }
    public Long getDurationMs() { return durationMs; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setScene(String scene) { this.scene = scene; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
    public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
    public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }
    public void setEstimated(Boolean estimated) { this.estimated = estimated; }
    public void setSuccess(Boolean success) { this.success = success; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
