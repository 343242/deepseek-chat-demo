package com.smart.rag.conversation.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 消息状态枚举
 */
public enum MessageStatus {

    IN_PROGRESS("IN_PROGRESS"),
    FINISHED("FINISHED"),
    ERROR("ERROR");

    @EnumValue
    @JsonValue
    private final String value;

    MessageStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
