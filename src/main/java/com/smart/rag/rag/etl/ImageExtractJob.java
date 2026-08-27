package com.smart.rag.rag.etl;

import java.io.Serializable;

/**
 * 图片提取任务消息体（design §6.3）。
 * <p>
 * manifest 不进消息体——消费者从 {@code document_image} 表读 PENDING 行，
 * 消息仅作触发器，DLQ 重放天然幂等（按行状态过滤）。
 * <p>
 * v1.3：移除 password 字段（明文持久化埋点）；启用加密 PDF 支持时再加回并评估加密。
 * v1.6 严重-2：消息不设 dedupKey——IdempotentHandler 的 SETNX 先标记后执行，
 * 崩溃窗口内重投会被判重静默 ACK；消费幂等由行级条件更新保证。
 */
public record ImageExtractJob(Long documentId, String bucket, String objectKey,
                              String fileName) implements Serializable {
}
