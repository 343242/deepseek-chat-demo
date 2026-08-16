package com.smart.rag.rag.dto;

/**
 * 批量删除单项结果（部分成功语义：单项失败不影响其余，见 deleteBatch）
 *
 * @param id      文档 ID
 * @param success 是否删除成功
 * @param message 失败原因（成功时为 null）
 */
public record DocumentDeleteResult(
    Long id,
    boolean success,
    String message
) {}
