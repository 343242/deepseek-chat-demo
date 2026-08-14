package com.smart.rag.rag.service;

import org.springframework.core.io.Resource;

/**
 * 已知长度的对象内容（统一存储读取契约，设计 §6）。
 *
 * @param resource      惰性 Resource，读取时才建立底层连接；实现 {@code Closeable}
 * @param offset        内容起始偏移（{@code Full} 为 0）
 * @param contentLength 内容长度（来自 range 或 stat，不通过读流计算）
 */
public record StoredObjectContent(
        Resource resource,
        long offset,
        long contentLength
) {}
