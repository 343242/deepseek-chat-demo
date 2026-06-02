package com.smart.rag.infrastructure.fallback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 动态模型候选配置
 * <p>
 * 启用后替代静态降级链，基于候选列表 + 熔断状态动态选择模型。
 * 首包探测（probe）可在流式场景下对无响应模型快速超时并降级。
 *
 * @param list                候选模型列表
 * @param probeEnabled        是否启用首包探测，默认 false
 * @param probeTimeoutSeconds 首包探测超时秒数，默认 10
 */
@ConfigurationProperties(prefix = "app.chat.candidates")
public record ChatCandidatesProperties(
        List<ModelCandidate> list,
        boolean probeEnabled,
        int probeTimeoutSeconds,
        boolean probeCacheEnabled,
        int probeCacheTtlSeconds,
        long preProbeIntervalMs
) {

    private static final Logger log = LoggerFactory.getLogger(ChatCandidatesProperties.class);

    public ChatCandidatesProperties {
        if (list == null) {
            list = List.of();
        }
        if (probeTimeoutSeconds <= 0) {
            log.warn("probeTimeoutSeconds={} is invalid, defaulting to 10", probeTimeoutSeconds);
            probeTimeoutSeconds = 10;
        }
        if (probeCacheTtlSeconds <= 0) {
            log.warn("probeCacheTtlSeconds={} is invalid, defaulting to 30", probeCacheTtlSeconds);
            probeCacheTtlSeconds = 30;
        }
        if (preProbeIntervalMs <= 0) {
            log.warn("preProbeIntervalMs={} is invalid, defaulting to 20000", preProbeIntervalMs);
            preProbeIntervalMs = 20_000L;
        }
    }
}
