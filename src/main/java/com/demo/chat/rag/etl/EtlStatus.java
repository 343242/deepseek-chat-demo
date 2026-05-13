package com.demo.chat.rag.etl;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * ETL 文档处理状态枚举
 * <p>
 * 定义文档从上传到处理完成的完整生命周期状态。
 * 通过 MyBatis-Plus {@code @EnumValue} 映射到数据库的字符串字段。
 */
public enum EtlStatus {

    UPLOADED("UPLOADED"),
    PARSING("PARSING"),
    CHUNKING("CHUNKING"),
    VECTORIZING("VECTORIZING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    VECTOR_FAILED("VECTOR_FAILED"),
    PROCESSING("PROCESSING"),

    /** ETL 前状态 — 不参与 ETL 状态机流转 */
    PENDING_APPROVAL("PENDING_APPROVAL"),
    REJECTED("REJECTED");

    @EnumValue
    private final String code;

    EtlStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
