package com.smart.rag.evaluation.dataset;

import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评估数据项状态枚举
 */
public enum EvaluationItemStatus {

    DRAFT("draft"),
    APPROVED("approved"),
    REJECTED("rejected");

    private static final Logger log = LoggerFactory.getLogger(EvaluationItemStatus.class);

    private final String value;

    EvaluationItemStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * 未知值降级为 DRAFT 并告警，而非抛异常——fromValue 被 RowMapper 调用，
     * 一条脏数据不应放倒整个列表查询。
     */
    public static EvaluationItemStatus fromValue(String value) {
        for (EvaluationItemStatus status : values()) {
            if (status.value.equalsIgnoreCase(value == null ? "" : value.trim())) {
                return status;
            }
        }
        log.warn("Unknown EvaluationItemStatus '{}', falling back to DRAFT", value);
        return DRAFT;
    }
}
