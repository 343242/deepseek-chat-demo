package com.demo.chat.rag.evaluation.runner;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 评估运行状态枚举
 */
public enum EvaluationRunStatus {

    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    EvaluationRunStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static EvaluationRunStatus fromValue(String value) {
        for (EvaluationRunStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown EvaluationRunStatus: " + value);
    }
}
