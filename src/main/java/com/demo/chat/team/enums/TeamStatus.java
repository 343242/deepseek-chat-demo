package com.demo.chat.team.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 团队状态枚举
 * <p>
 * DB 存 int（@EnumValue），API 返回字符串（@JsonValue → name()）
 */
public enum TeamStatus {

    INACTIVE(0),
    ACTIVE(1);

    @EnumValue
    private final int code;

    TeamStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @JsonValue
    public String getName() {
        return name();
    }
}
