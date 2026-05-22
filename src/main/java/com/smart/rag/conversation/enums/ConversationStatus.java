package com.smart.rag.conversation.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会话状态枚举
 */
public enum ConversationStatus {

    ACTIVE("ACTIVE"),
    ARCHIVED("ARCHIVED"),
    DELETED("DELETED");

    @EnumValue
    @JsonValue
    private final String value;

    ConversationStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
