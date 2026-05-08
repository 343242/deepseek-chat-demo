package com.demo.deepseekchat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Token 用量记录
 * <p>
 * 记录每次 API 调用的 token 消耗和耗时，用于统计和成本追踪。
 */
@TableName("token_usage")
public class TokenUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

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

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public TokenUsage() {
    }

    public TokenUsage(String conversationId, String modelId,
                      long promptTokens, long completionTokens, long totalTokens,
                      long durationMs) {
        this.conversationId = conversationId;
        this.modelId = modelId;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.durationMs = durationMs;
        this.createdAt = LocalDateTime.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getConversationId() { return conversationId; }
    public String getModelId() { return modelId; }
    public Long getPromptTokens() { return promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public Long getTotalTokens() { return totalTokens; }
    public Long getDurationMs() { return durationMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // Setters
    public void setId(Long id) { this.id = id; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setPromptTokens(Long promptTokens) { this.promptTokens = promptTokens; }
    public void setCompletionTokens(Long completionTokens) { this.completionTokens = completionTokens; }
    public void setTotalTokens(Long totalTokens) { this.totalTokens = totalTokens; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
