package com.demo.chat.conversation.enums;

/**
 * 消息状态枚举
 */
public enum MessageStatus {

    IN_PROGRESS("生成中"),
    FINISHED("已完成"),
    ERROR("出错");

    private final String description;

    MessageStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
