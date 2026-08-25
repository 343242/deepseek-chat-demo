package com.smart.rag.infrastructure.llm.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 候选级超时参数（design llm-resilience-optimization WS2，P5）。
 * <p>
 * 从 candidate {@code params} 读取 5 个 kebab-case 键；解析风格对齐
 * {@code BailianEmbeddingClient.resolveBatchSize}：Number/可解析 String，
 * 非法值回落默认 + WARN（超时只影响可用性，fail-fast 无收益）。
 * <ul>
 *   <li>{@code connect-timeout-ms} — 连接超时（默认 10000）</li>
 *   <li>{@code read-timeout-ms} — 阻塞读超时（按能力默认）</li>
 *   <li>{@code call-timeout-ms} — 阻塞调用总时长上限，OkHttp callTimeout（按能力默认）</li>
 *   <li>{@code stream-read-timeout-ms} — 流式相邻 chunk 间隔上限（默认 120000）</li>
 *   <li>{@code stream-call-timeout-ms} — 单流总时长上限（默认 300000；0 = 不限）</li>
 * </ul>
 * 关键语义：阻塞路径现状无 callTimeout 为<b>无界</b>（慢速滴流连接可无限期占用线程）——
 * 任何有限值都是改善；chat 阻塞默认 150s 保证 read-timeout 120s 仍先触发（走既有可重试路径）。
 */
public record TimeoutParams(
    long connectTimeoutMs,
    long readTimeoutMs,
    long callTimeoutMs,
    long streamReadTimeoutMs,
    long streamCallTimeoutMs
) {

    private static final Logger log = LoggerFactory.getLogger(TimeoutParams.class);

    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    public static final long DEFAULT_STREAM_READ_TIMEOUT_MS = 120_000;
    public static final long DEFAULT_STREAM_CALL_TIMEOUT_MS = 300_000;

    /** chat 能力阻塞读超时默认（慢推理模型长 chunk 提取可超 60s） */
    public static final long DEFAULT_CHAT_READ_TIMEOUT_MS = 120_000;
    /** chat 能力阻塞 call-timeout 默认（M5：> read-timeout，保证纯读停滞由 read 先触发） */
    public static final long DEFAULT_CHAT_CALL_TIMEOUT_MS = 150_000;
    /** 其余能力（embedding/rerank）阻塞 call-timeout 默认 */
    public static final long DEFAULT_OTHER_CALL_TIMEOUT_MS = 180_000;

    public static TimeoutParams chatDefaults() {
        return new TimeoutParams(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_CHAT_READ_TIMEOUT_MS,
            DEFAULT_CHAT_CALL_TIMEOUT_MS, DEFAULT_STREAM_READ_TIMEOUT_MS, DEFAULT_STREAM_CALL_TIMEOUT_MS);
    }

    public static TimeoutParams otherDefaults(long readTimeoutMs) {
        return new TimeoutParams(DEFAULT_CONNECT_TIMEOUT_MS, readTimeoutMs,
            DEFAULT_OTHER_CALL_TIMEOUT_MS, DEFAULT_STREAM_READ_TIMEOUT_MS, DEFAULT_STREAM_CALL_TIMEOUT_MS);
    }

    /** 从 candidate params 解析，未配置/非法键回落到本实例默认值 */
    public TimeoutParams mergeWithParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return this;
        }
        return new TimeoutParams(
            resolveMs(params, "connect-timeout-ms", connectTimeoutMs),
            resolveMs(params, "read-timeout-ms", readTimeoutMs),
            resolveMs(params, "call-timeout-ms", callTimeoutMs),
            resolveMs(params, "stream-read-timeout-ms", streamReadTimeoutMs),
            resolveMs(params, "stream-call-timeout-ms", streamCallTimeoutMs));
    }

    private static long resolveMs(Map<String, Object> params, String key, long fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && number.longValue() >= 0) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                long parsed = Long.parseLong(s.trim());
                if (parsed >= 0) return parsed;
            } catch (NumberFormatException ignored) {
                // 回退默认值
            }
        }
        log.warn("候选 params.{} 非法（{}），回落默认 {}ms", key, value, fallback);
        return fallback;
    }
}
