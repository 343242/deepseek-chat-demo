package com.smart.rag.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * 模型系统提示词配置
 * <p>
 * 每个模型可设置独立的 system prompt，支持动态修改无需重启。
 */
@TableName("system_prompt")
public class SystemPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("model_id")
    private String modelId;

    @TableField("prompt_text")
    private String promptText;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    public SystemPrompt() {
    }

    public SystemPrompt(String modelId, String promptText) {
        this.modelId = modelId;
        this.promptText = promptText;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public void updatePrompt(String promptText) {
        this.promptText = promptText;
        this.updatedAt = OffsetDateTime.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getModelId() { return modelId; }
    public String getPromptText() { return promptText; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // Setters
    public void setId(Long id) { this.id = id; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setPromptText(String promptText) { this.promptText = promptText; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
