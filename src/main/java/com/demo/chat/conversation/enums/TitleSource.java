package com.demo.chat.conversation.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会话标题来源枚举
 */
public enum TitleSource {

    /** 系统自动生成（取第一条消息前 20 字） */
    SYSTEM("SYSTEM"),

    /** 用户手动编辑 */
    USER("USER");

    @EnumValue
    @JsonValue
    private final String value;

    TitleSource(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
