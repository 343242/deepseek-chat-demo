package com.demo.chat.team.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 团队状态枚举
 */
public enum TeamStatus {

    DISABLED(0),
    ENABLED(1);

    @EnumValue
    private final int code;

    TeamStatus(int code) {
        this.code = code;
    }

    @JsonValue
    public int getCode() {
        return code;
    }
}
