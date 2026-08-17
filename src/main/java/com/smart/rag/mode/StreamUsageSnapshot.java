package com.smart.rag.mode;

import org.jspecify.annotations.Nullable;

/**
 * 流式请求终局用量快照 — 策略层在流完成时写入，SSE 桥接层发 {@code event:usage} 尾帧。
 *
 * @param tokenUsage 本次流式请求累计总 token（多轮工具调用求和），{@code null} 表示厂商未返回 usage
 * @param durationMs 流式请求耗时（毫秒）
 */
public record StreamUsageSnapshot(@Nullable Integer tokenUsage, long durationMs) {
}
