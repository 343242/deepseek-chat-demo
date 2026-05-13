package com.demo.chat.team.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审批状态枚举
 */
public enum ApprovalStatus {

    PENDING(0),
    APPROVED(1),
    REJECTED(2);

    @EnumValue
    private final int code;

    ApprovalStatus(int code) {
        this.code = code;
    }

    @JsonValue
    public int getCode() {
        return code;
    }
}
