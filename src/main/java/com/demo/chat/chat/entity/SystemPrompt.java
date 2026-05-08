package com.demo.chat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

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
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public SystemPrompt() {
    }

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

    // Setters
    public void setId(Long id) { this.id = id; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setPromptText(String promptText) { this.promptText = promptText; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
