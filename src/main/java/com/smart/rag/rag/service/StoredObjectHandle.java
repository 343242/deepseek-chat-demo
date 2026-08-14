package com.smart.rag.rag.service;

/**
 * 已 stat 的对象句柄（统一存储读取契约，设计 §6）。
 * <p>
 * 由 {@code FileStorageService.open(bucket, objectKey)} 创建，携带对象的真实总大小；
 * HEAD 场景只使用 {@link #totalSize()}，不调用 {@link #content(ObjectReadRange)} 打开内容流。
 */
public interface StoredObjectHandle {

    /** 对象真实总大小（字节，来自 statObject） */
    long totalSize();

    /**
     * 打开内容流得到惰性 {@link StoredObjectContent}。
     * <p>
     * 返回的 Resource 在被真正读取时才建立底层连接；调用方负责在
     * 正常结束、异常与客户端断开时关闭（Resource 实现 {@code Closeable}）。
     */
    StoredObjectContent content(ObjectReadRange range);
}
