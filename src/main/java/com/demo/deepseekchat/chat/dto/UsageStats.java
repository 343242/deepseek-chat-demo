package com.demo.deepseekchat.chat.dto;

/**
 * 用量统计聚合 DTO
 *
 * @param groupKey      聚合键（modelId 或 conversationId）
 * @param requestCount  请求次数
 * @param totalPromptTokens    总输入 token
 * @param totalCompletionTokens 总输出 token
 * @param totalTokens  总 token
 * @param avgDurationMs 平均耗时 (ms)
 */
public record UsageStats(
    String groupKey,
    long requestCount,
    long totalPromptTokens,
    long totalCompletionTokens,
    long totalTokens,
    double avgDurationMs
) {}
