package com.smart.rag.rag.service;

/**
 * 对象读取范围（统一存储读取契约，设计 §6）。
 * <p>
 * {@link Full} 读取完整对象；{@link Bytes} 读取精确区间，offset/length 语义与
 * MinIO {@code getObject(offset, length)} 一致。
 */
public sealed interface ObjectReadRange {

    /** 完整对象 */
    record Full() implements ObjectReadRange {}

    /** 精确区间：[offset, offset + length) */
    record Bytes(long offset, long length) implements ObjectReadRange {}
}
