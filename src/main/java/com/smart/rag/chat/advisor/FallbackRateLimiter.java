package com.smart.rag.chat.advisor;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 分布式限流器 -- Redisson RRateLimiter + 内存降级
 * <p>
 * 正常情况下使用 Redisson 的 RRateLimiter 实现跨实例共享限流。
 * Redis 不可用时自动降级到内存 TokenBucketLimiter，
 * 降级后每个实例独立限流，总配额会随实例数放大。
 * <p>
 * 降级恢复策略：每次 tryAcquire 在 fallback 路径中以约 1/10 概率尝试探测 Redis 连通性，
 * 避免短暂 Redis 抖动导致长达数分钟的降级。
 */
public class FallbackRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FallbackRateLimiter.class);

    private static final String KEY_PREFIX = "smart-rag:ratelimit:";

    /** 降级时每次 tryAcquire 探测 Redis 恢复的概率倒数（即约 1/N 的请求触发探测） */
    private static final int RECOVERY_PROBE_INV_PROB = 10;

    private final RedissonClient redissonClient;
    private final TokenBucketLimiter fallback;
    private final long ratePerSecond;

    /** 缓存已初始化 rate 的 limiter，避免每次 trySetRate 的 Redis 往返 */
    private final ConcurrentMap<String, RRateLimiter> limiterCache = new ConcurrentHashMap<>();

    private volatile boolean usingFallback = false;

    public FallbackRateLimiter(RedissonClient redissonClient,
                               TokenBucketLimiter fallback,
                               double refillRate) {
        this.redissonClient = redissonClient;
        this.fallback = fallback;
        // RRateLimiter 语义: rate permits/sec，对齐内存版的 refillRate
        this.ratePerSecond = (long) refillRate;
    }

    @Override
    public boolean tryAcquire(String key) {
        if (!usingFallback) {
            try {
                RRateLimiter limiter = limiterCache.computeIfAbsent(
                        key, k -> initLimiter(KEY_PREFIX + k));
                return limiter.tryAcquire(1);
            } catch (Exception e) {
                log.warn("Redis rate limiter failed, falling back to in-memory: {}", e.getMessage());
                usingFallback = true;
            }
        }
        // Probabilistic recovery probe: ~1/N requests attempt to reconnect to Redis
        if (usingFallback && ThreadLocalRandom.current().nextInt(RECOVERY_PROBE_INV_PROB) == 0) {
            attemptRecovery();
        }
        return fallback.tryAcquire(key);
    }

    private RRateLimiter initLimiter(String redisKey) {
        RRateLimiter limiter = redissonClient.getRateLimiter(redisKey);
        // Note: Redisson RRateLimiter uses a fixed rate (permits per interval) without burst capacity.
        // This is a sustained-rate limiter, stricter than the in-memory token bucket which allows burst
        // up to its maxTokens (capacity=10). If burst tolerance is needed, a different approach is required.
        limiter.trySetRate(RateType.OVERALL, ratePerSecond, Duration.ofSeconds(1));
        return limiter;
    }

    /** 是否正在使用内存降级（用于监控） */
    public boolean isUsingFallback() {
        return usingFallback;
    }

    /** 尝试从降级模式恢复到分布式限流 */
    public void attemptRecovery() {
        if (usingFallback) {
            try {
                // 轻量级连通性探测（等同 Redis DBSIZE，O(1)）
                redissonClient.getKeys().countExists();
                usingFallback = false;
                log.info("Redis recovered, switching back to distributed rate limiter");
            } catch (Exception e) {
                log.debug("Redis still unavailable, staying in fallback mode");
            }
        }
    }
}
