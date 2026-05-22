package com.smart.rag.rag.evaluation.dataset;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 评估数据项状态枚举
 */
public enum EvaluationItemStatus {

    DRAFT("draft"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String value;

    EvaluationItemStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static EvaluationItemStatus fromValue(String value) {
        for (EvaluationItemStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("Unknown EvaluationItemStatus: " + value);
    }
}
