package com.smart.rag.usage.dto;

/**
 * 时间桶粒度 — GET /api/usage/timeline?granularity=
 * <p>
 * 枚举绑定即白名单；truncUnit/step 作为绑定参数传入 date_trunc/generate_series。
 */
public enum TimelineGranularity {

    DAY("day", "1 day"),
    MONTH("month", "1 month");

    private final String truncUnit;
    private final String step;

    TimelineGranularity(String truncUnit, String step) {
        this.truncUnit = truncUnit;
        this.step = step;
    }

    /** PostgreSQL date_trunc 第一个参数（text） */
    public String truncUnit() {
        return truncUnit;
    }

    /** generate_series 步长（interval 字面量） */
    public String step() {
        return step;
    }
}
