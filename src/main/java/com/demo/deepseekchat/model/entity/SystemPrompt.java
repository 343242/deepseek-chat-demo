package com.demo.deepseekchat.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 模型系统提示词配置
 * <p>
 * 每个模型可设置独立的 system prompt，支持动态修改无需重启。
 */
@Entity
@Table(name = "system_prompt", uniqueConstraints = @UniqueConstraint(columnNames = "model_id"))
public class SystemPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false, unique = true, length = 100)
    private String modelId;

    @Column(name = "prompt_text", columnDefinition = "TEXT", nullable = false)
    private String promptText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected SystemPrompt() {}

    public SystemPrompt(String modelId, String promptText) {
        this.modelId = modelId;
        this.promptText = promptText;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updatePrompt(String promptText) {
        this.promptText = promptText;
        this.updatedAt = LocalDateTime.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getModelId() { return modelId; }
    public String getPromptText() { return promptText; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
