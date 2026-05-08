package com.demo.chat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 模型运行时参数配置
 * <p>
 * 支持动态调整 temperature、maxTokens、topP 等参数，无需重启服务。
 */
@TableName("model_params")
public class ModelParams {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("model_id")
    private String modelId;

    @TableField("temperature")
    private Double temperature;

    @TableField("max_tokens")
    private Integer maxTokens;

    @TableField("top_p")
    private Double topP;

    @TableField("frequency_penalty")
    private Double frequencyPenalty;

    @TableField("presence_penalty")
    private Double presencePenalty;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public ModelParams() {
    }

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

    // Setters (needed for MyBatis-Plus)
    public void setId(Long id) { this.id = id; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public void setTopP(Double topP) { this.topP = topP; }
    public void setFrequencyPenalty(Double frequencyPenalty) { this.frequencyPenalty = frequencyPenalty; }
    public void setPresencePenalty(Double presencePenalty) { this.presencePenalty = presencePenalty; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
