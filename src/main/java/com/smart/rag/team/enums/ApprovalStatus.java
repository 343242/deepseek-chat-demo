package com.smart.rag.team.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 审批状态枚举
 * <p>
 * DB 存 int（@EnumValue），API 返回字符串（@JsonValue → name()）
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

    public int getCode() {
        return code;
    }

    @JsonValue
    public String getName() {
        return name();
    }
}
