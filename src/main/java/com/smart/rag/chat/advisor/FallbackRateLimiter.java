package com.smart.rag.chat.advisor;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 分布式限流器 -- Redisson RRateLimiter + 内存降级
 * <p>
 * 正常情况下使用 Redisson 的 RRateLimiter 实现跨实例共享限流。
 * Redis 不可用时自动降级到内存 TokenBucketLimiter，
 * 降级后每个实例独立限流，总配额会随实例数放大。
 */
public class FallbackRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FallbackRateLimiter.class);

    private static final String KEY_PREFIX = "smart-rag:ratelimit:";

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
        return fallback.tryAcquire(key);
    }

    private RRateLimiter initLimiter(String redisKey) {
        RRateLimiter limiter = redissonClient.getRateLimiter(redisKey);
        limiter.trySetRate(RateType.OVERALL, ratePerSecond, 1, RateIntervalUnit.SECONDS);
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
                // 用轻量级 Redis 操作检测连通性
                redissonClient.getKeys().countExists();
                usingFallback = false;
                log.info("Redis recovered, switching back to distributed rate limiter");
            } catch (Exception e) {
                log.debug("Redis still unavailable, staying in fallback mode");
            }
        }
    }
}
