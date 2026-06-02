package com.smart.rag.infrastructure.fallback.cache;

import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 模型健康状态 Redis 缓存
 * <p>
 * 使用 Redisson {@link RMapCache} 实现 per-entry TTL：
 * <ul>
 *   <li>HEALTHY 条目 TTL = healthyTtlSeconds（默认 30s）</li>
 *   <li>UNHEALTHY 条目 TTL = unhealthyTtlSeconds（默认 15s）</li>
 * </ul>
 */
public class ModelHealthCache {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthCache.class);
    static final String CACHE_NAME = "model:health";

    private final RMapCache<String, HealthEntry> cache;
    private final int healthyTtlSeconds;
    private final int unhealthyTtlSeconds;

    public ModelHealthCache(RedissonClient redisson,
                            int healthyTtlSeconds,
                            int unhealthyTtlSeconds) {
        this.cache = redisson.getMapCache(CACHE_NAME);
        this.healthyTtlSeconds = healthyTtlSeconds;
        this.unhealthyTtlSeconds = unhealthyTtlSeconds;
    }

    public HealthEntry get(String modelId) {
        return cache.get(modelId);
    }

    public void putHealthy(String modelId, long latencyMs) {
        HealthEntry entry = new HealthEntry(modelId, HealthStatus.HEALTHY,
                Instant.now().toEpochMilli(), latencyMs);
        cache.put(modelId, entry, healthyTtlSeconds, TimeUnit.SECONDS);
        log.debug("Cached HEALTHY '{}' (latency={}ms, ttl={}s)", modelId, latencyMs, healthyTtlSeconds);
    }

    public void putUnhealthy(String modelId) {
        HealthEntry entry = new HealthEntry(modelId, HealthStatus.UNHEALTHY,
                Instant.now().toEpochMilli(), -1);
        cache.put(modelId, entry, unhealthyTtlSeconds, TimeUnit.SECONDS);
        log.debug("Cached UNHEALTHY '{}' (ttl={}s)", modelId, unhealthyTtlSeconds);
    }
}
