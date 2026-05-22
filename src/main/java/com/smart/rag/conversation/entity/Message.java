package com.smart.rag.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smart.rag.conversation.enums.MessageStatus;

import java.time.OffsetDateTime;

/**
 * 消息实体
 * <p>
 * 会话内的每一条 user/assistant 消息。
 * 通过 parent_id 支持树形结构（分支对话、重新生成）。
 */
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("parent_id")
    private Long parentId;

    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("status")
    private MessageStatus status;

    @TableField("model_id")
    private String modelId;

    @TableField("thinking_enabled")
    private Boolean thinkingEnabled;

    @TableField("token_usage")
    private Integer tokenUsage;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    public Message() {
    }

    /**
     * 创建 USER 消息的便捷构造器
     */
    public static Message userMessage(String conversationId, Long parentId, String content) {
        Message msg = new Message();
        msg.conversationId = conversationId;
        msg.parentId = parentId;
        msg.role = "USER";
        msg.content = content;
        msg.status = MessageStatus.FINISHED;
        msg.thinkingEnabled = false;
        msg.createdAt = OffsetDateTime.now();
        msg.updatedAt = OffsetDateTime.now();
        return msg;
    }

    /**
     * 创建 ASSISTANT 消息的便捷构造器
     */
    public static Message assistantMessage(String conversationId, Long parentId,
                                           String content, String modelId,
                                           int tokenUsage, long durationMs) {
        Message msg = new Message();
        msg.conversationId = conversationId;
        msg.parentId = parentId;
        msg.role = "ASSISTANT";
        msg.content = content;
        msg.status = MessageStatus.FINISHED;
        msg.modelId = modelId;
        msg.thinkingEnabled = false;
        msg.tokenUsage = tokenUsage;
        msg.durationMs = durationMs;
        msg.createdAt = OffsetDateTime.now();
        msg.updatedAt = OffsetDateTime.now();
        return msg;
    }

    // ==================== Getters ====================

    public Long getId() { return id; }
    public String getConversationId() { return conversationId; }
    public Long getParentId() { return parentId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public MessageStatus getStatus() { return status; }
    public String getModelId() { return modelId; }
    public Boolean getThinkingEnabled() { return thinkingEnabled; }
    public Integer getTokenUsage() { return tokenUsage; }
    public Long getDurationMs() { return durationMs; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // ==================== Setters ====================

    public void setId(Long id) { this.id = id; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public void setRole(String role) { this.role = role; }
    public void setContent(String content) { this.content = content; }
    public void setStatus(MessageStatus status) { this.status = status; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setThinkingEnabled(Boolean thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }
    public void setTokenUsage(Integer tokenUsage) { this.tokenUsage = tokenUsage; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    // ==================== 业务方法 ====================

    public boolean isUser() {
        return "USER".equals(this.role);
    }

    public boolean isFinished() {
        return MessageStatus.FINISHED == this.status;
    }
}
