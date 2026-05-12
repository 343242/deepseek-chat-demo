package com.demo.chat.conversation.enums;

/**
 * 会话状态枚举
 */
public enum ConversationStatus {

    ACTIVE("活跃"),
    ARCHIVED("已归档"),
    DELETED("已删除");

    private final String description;

    ConversationStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
