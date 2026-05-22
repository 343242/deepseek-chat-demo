package com.smart.rag.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smart.rag.conversation.enums.ConversationStatus;
import com.smart.rag.conversation.enums.TitleSource;

import java.time.OffsetDateTime;

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
    private TitleSource titleSource;

    @TableField("model_id")
    private String modelId;

    @TableField("pinned")
    private Boolean pinned;

    @TableField("status")
    private ConversationStatus status;

    @TableField("message_count")
    private Integer messageCount;

    @TableField("last_message_at")
    private OffsetDateTime lastMessageAt;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    public Conversation() {
    }

    /**
     * 创建新会话的便捷构造器
     */
    public Conversation(String conversationId, Long userId, String modelId) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.modelId = modelId;
        this.titleSource = TitleSource.SYSTEM;
        this.pinned = false;
        this.status = ConversationStatus.ACTIVE;
        this.messageCount = 0;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    // ==================== Getters ====================

    public Long getId() { return id; }
    public String getConversationId() { return conversationId; }
    public Long getUserId() { return userId; }
    public String getTitle() { return title; }
    public TitleSource getTitleSource() { return titleSource; }
    public String getModelId() { return modelId; }
    public Boolean getPinned() { return pinned; }
    public ConversationStatus getStatus() { return status; }
    public Integer getMessageCount() { return messageCount; }
    public OffsetDateTime getLastMessageAt() { return lastMessageAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // ==================== Setters ====================

    public void setId(Long id) { this.id = id; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setTitle(String title) { this.title = title; }
    public void setTitleSource(TitleSource titleSource) { this.titleSource = titleSource; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
    public void setStatus(ConversationStatus status) { this.status = status; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public void setLastMessageAt(OffsetDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    // ==================== 业务方法 ====================

    public boolean isActive() {
        return ConversationStatus.ACTIVE == this.status;
    }

    public boolean isTitleUserEdited() {
        return TitleSource.USER == this.titleSource;
    }

    /** 是否需要自动生成标题（系统生成 + 标题为空 + 首条消息） */
    public boolean needsAutoTitle() {
        return titleSource == TitleSource.SYSTEM
                && title == null
                && (messageCount == null || messageCount == 0);
    }
}
