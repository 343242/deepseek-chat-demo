package com.smart.rag.infrastructure.llm.usage;

import org.jspecify.annotations.Nullable;

/**
 * 单次模型调用的用量采样 — 装饰器产出一手数据，{@link UsageEventSink} 消费。
 * <p>
 * token 为 {@code null} 表示未知（厂商未返回 usage 且无估算依据，如调用失败）；
 * 非空且 {@code estimated=true} 时为字符数/4 的估算值。
 *
 * @param context          归因上下文（装配时绑定）
 * @param promptTokens     输入 token 数，{@code null} 表未知
 * @param completionTokens 输出 token 数，{@code null} 表未知
 * @param estimated        token 是否为估算值（真实 usage 缺失时按字符数/4 兜底）
 * @param success          调用是否成功（错误/取消的调用也采样，供成功率统计）
 * @param durationMs       模型调用耗时（毫秒）
 */
public record UsageSample(
    UsageContext context,
    @Nullable Long promptTokens,
    @Nullable Long completionTokens,
    boolean estimated,
    boolean success,
    long durationMs
) {
}
