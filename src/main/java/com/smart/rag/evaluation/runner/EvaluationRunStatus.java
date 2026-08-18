package com.smart.rag.evaluation.runner;

import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评估运行状态枚举
 */
public enum EvaluationRunStatus {

    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunStatus.class);

    private final String value;

    EvaluationRunStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 未知值降级为 PENDING 并告警，而非抛异常——fromValue 被 RowMapper 调用，
     * 一条脏数据不应放倒整个列表查询。
     */
    public static EvaluationRunStatus fromValue(String value) {
        for (EvaluationRunStatus status : values()) {
            if (status.value.equalsIgnoreCase(value == null ? "" : value.trim())) {
                return status;
            }
        }
        log.warn("Unknown EvaluationRunStatus '{}', falling back to PENDING", value);
        return PENDING;
    }
}
