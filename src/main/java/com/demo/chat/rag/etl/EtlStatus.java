package com.demo.chat.rag.etl;

/**
 * ETL 文档状态常量
 * <p>
 * 替代散布在多个类中的硬编码状态字符串。
 */
public final class EtlStatus {

    private EtlStatus() {}

    public static final String UPLOADED = "UPLOADED";
    public static final String PARSING = "PARSING";
    public static final String CHUNKING = "CHUNKING";
    public static final String VECTORIZING = "VECTORIZING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String VECTOR_FAILED = "VECTOR_FAILED";
    public static final String PROCESSING = "PROCESSING";
}
