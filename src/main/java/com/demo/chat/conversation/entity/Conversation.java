package com.demo.chat.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.demo.chat.conversation.enums.ConversationStatus;
import com.demo.chat.conversation.enums.TitleSource;

import java.time.LocalDateTime;

/**
 * 会话实体
 * <p>
 * 用户的对话容器，有标题、置顶、状态等元数据。
 * 通过 conversation_id 和 spring_ai_chat_memory 逻辑关联。
 */
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("user_id")
    private Long userId;

    @TableField("title")
    private String title;

    @TableField("title_source")
    private String titleSource;

    @TableField("model_id")
    private String modelId;

    @TableField("pinned")
    private Boolean pinned;

    @TableField("status")
    private String status;

    @TableField("message_count")
    private Integer messageCount;

    @TableField("last_message_at")
    private LocalDateTime lastMessageAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Conversation() {
    }

    /**
     * 创建新会话的便捷构造器
     */
    public Conversation(String conversationId, Long userId, String modelId) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.modelId = modelId;
        this.titleSource = TitleSource.SYSTEM.name();
        this.pinned = false;
        this.status = ConversationStatus.ACTIVE.name();
        this.messageCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== Getters ====================

    public Long getId() { return id; }
    public String getConversationId() { return conversationId; }
    public Long getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getTitleSource() { return titleSource; }
    public String getModelId() { return modelId; }
    public Boolean getPinned() { return pinned; }
    public String getStatus() { return status; }
    public Integer getMessageCount() { return messageCount; }
    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ==================== Setters ====================

    public void setId(Long id) { this.id = id; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setTitle(String title) { this.title = title; }
    public void setTitleSource(String titleSource) { this.titleSource = titleSource; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
    public void setStatus(String status) { this.status = status; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public void setLastMessageAt(LocalDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // ==================== 业务方法 ====================

    public boolean isActive() {
        return ConversationStatus.ACTIVE.name().equals(this.status);
    }

    public boolean isTitleUserEdited() {
        return TitleSource.USER.name().equals(this.titleSource);
    }
}
