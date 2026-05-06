package com.demo.deepseekchat.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 模型运行时参数配置
 * <p>
 * 支持动态调整 temperature、maxTokens、topP 等参数，无需重启服务。
 */
@Entity
@Table(name = "model_params", uniqueConstraints = @UniqueConstraint(columnNames = "model_id"))
public class ModelParams {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false, unique = true, length = 100)
    private String modelId;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "top_p")
    private Double topP;

    @Column(name = "frequency_penalty")
    private Double frequencyPenalty;

    @Column(name = "presence_penalty")
    private Double presencePenalty;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected ModelParams() {}

    public ModelParams(String modelId) {
        this.modelId = modelId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void applyUpdates(Double temperature, Integer maxTokens, Double topP,
                             Double frequencyPenalty, Double presencePenalty) {
        if (temperature != null) this.temperature = temperature;
        if (maxTokens != null) this.maxTokens = maxTokens;
        if (topP != null) this.topP = topP;
        if (frequencyPenalty != null) this.frequencyPenalty = frequencyPenalty;
        if (presencePenalty != null) this.presencePenalty = presencePenalty;
        this.updatedAt = LocalDateTime.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getModelId() { return modelId; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public Double getTopP() { return topP; }
    public Double getFrequencyPenalty() { return frequencyPenalty; }
    public Double getPresencePenalty() { return presencePenalty; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
