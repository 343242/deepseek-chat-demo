package com.demo.deepseekchat.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Token 用量记录
 * <p>
 * 记录每次 API 调用的 token 消耗和耗时，用于统计和成本追踪。
 */
@Entity
@Table(name = "token_usage", indexes = {
        @Index(name = "idx_token_usage_model", columnList = "model_id"),
        @Index(name = "idx_token_usage_conversation", columnList = "conversation_id"),
        @Index(name = "idx_token_usage_created_at", columnList = "created_at")
})
public class TokenUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 100)
    private String conversationId;

    @Column(name = "model_id", nullable = false, length = 100)
    private String modelId;

    @Column(name = "prompt_tokens", nullable = false)
    private Long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private Long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private Long totalTokens;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected TokenUsage() {}

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
}
