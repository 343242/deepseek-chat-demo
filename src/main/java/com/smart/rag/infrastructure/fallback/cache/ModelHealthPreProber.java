package com.smart.rag.infrastructure.fallback.cache;

import com.smart.rag.infrastructure.fallback.ChatCandidatesProperties;
import com.smart.rag.infrastructure.fallback.ModelCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * 后台定时预探测任务
 * <p>
 * 定期扫描所有启用的候选模型，对缓存即将过期或无缓存条目的模型发起预探测，
 * 确保请求到来时缓存已预热。
 * <p>
 * 非 {@code @Component}，由 {@link com.smart.rag.infrastructure.fallback.FallbackAutoConfiguration} 创建。
 */
public class ModelHealthPreProber {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthPreProber.class);

    /** 缓存条目剩余 TTL 超过总 TTL 的此比例时视为"新鲜"，跳过预探测 */
    private static final long FRESH_THRESHOLD_DIVISOR = 2L;

    private final ChatCandidatesProperties props;
    private final ModelHealthCache healthCache;

    /** 可选的探测回调，由配置层注入 */
    private final ProbeFunction probeFunction;

    /**
     * 探测函数接口 — 接收 modelId，返回探测延迟（毫秒），失败时返回 -1
     */
    @FunctionalInterface
    public interface ProbeFunction {
        long probe(String modelId);
    }

    public ModelHealthPreProber(ChatCandidatesProperties props,
                                ModelHealthCache healthCache,
                                ProbeFunction probeFunction) {
        this.props = props;
        this.healthCache = healthCache;
        this.probeFunction = probeFunction;
    }

    /**
     * 执行一次预探测扫描。由外部调度器（@Scheduled）调用。
     */
    public void preProbe() {
        List<ModelCandidate> candidates = props.list();
        if (candidates.isEmpty()) {
            return;
        }

        int probed = 0;
        int skipped = 0;
        long now = Instant.now().toEpochMilli();

        for (ModelCandidate candidate : candidates) {
            if (!candidate.enabled()) {
                continue;
            }

            String modelId = candidate.compositeId();
            HealthEntry cached = healthCache.get(modelId);

            if (cached != null && cached.isHealthy()) {
                long totalTtlMs = props.probeCacheTtlSeconds() * 1000L;
                long remainingTtl = (cached.timestamp() + totalTtlMs) - now;
                if (remainingTtl > totalTtlMs / FRESH_THRESHOLD_DIVISOR) {
                    skipped++;
                    continue;
                }
            }

            long latency = probeFunction.probe(modelId);
            probed++;
            if (latency >= 0) {
                healthCache.putHealthy(modelId, latency);
            } else {
                healthCache.putUnhealthy(modelId);
            }
        }

        log.debug("Pre-probe sweep: {} candidates, {} probed, {} skipped",
                candidates.size(), probed, skipped);
    }
}
