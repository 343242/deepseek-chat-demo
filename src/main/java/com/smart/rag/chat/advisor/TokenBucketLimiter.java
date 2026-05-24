package com.smart.rag.chat.advisor;

import com.smart.rag.config.AdvisorAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 令牌桶限流器
 * <p>
 * 支持按 key（如 conversationId）独立限流。
 * 每个令牌桶独立维护：容量、当前令牌数、上次补充时间。
 * <p>
 * 线程安全：Bucket 所有操作均在 ReentrantLock 保护下完成（避免虚拟线程 Pinning）。
 * 内存安全：通过 {@link #cleanIdleBuckets()} 定期清理空闲桶，
 * 由 {@link AdvisorAutoConfiguration} 通过 @Scheduled 调用。
 */
public class TokenBucketLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    /** 桶数量硬上限，防止 key 爆炸致 OOM */
    static final int MAX_BUCKETS = 10_000;

    /** 触发即时清理的阈值比例 */
    private static final double CLEANUP_THRESHOLD_RATIO = 0.8;

    private final long maxTokens;
    private final double refillRate;
    private final Duration maxIdle;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param maxTokens 桶容量（最大令牌数）
     * @param refillRate 每秒补充令牌数
     * @param maxIdle   桶最大空闲时间，超时清理
     */
    public TokenBucketLimiter(long maxTokens, double refillRate, Duration maxIdle) {
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0, got: " + maxTokens);
        if (refillRate <= 0) throw new IllegalArgumentException("refillRate must be > 0, got: " + refillRate);
        Assert.notNull(maxIdle, "maxIdle must not be null");
        this.maxTokens = maxTokens;
        this.refillRate = refillRate;
        this.maxIdle = maxIdle;
    }

    @Override
    public boolean tryAcquire(String key) {
        // 已有桶直接消费，不受上限限制
        Bucket existing = buckets.get(key);
        if (existing != null) {
            return existing.tryConsume(1);
        }

        // 接近上限时同步清理空闲桶，防止孤儿桶累积
        if (buckets.size() > (int)(MAX_BUCKETS * CLEANUP_THRESHOLD_RATIO)) {
            int cleaned = cleanIdleBuckets();
            if (cleaned > 0) {
                log.info("Eager cleanup: removed {} idle buckets, {} remaining", cleaned, buckets.size());
            }
        }

        if (buckets.size() >= MAX_BUCKETS) {
            String displayKey = key.length() > 20 ? key.substring(0, 20) + "..." : key;
            log.warn("Token bucket limit reached ({}), rejecting new key: {}", MAX_BUCKETS, displayKey);
            return false;
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxTokens, refillRate));
        return bucket.tryConsume(1);
    }

    /**
     * 获取指定 key 的剩余令牌数（用于监控）
     */
    public long getAvailableTokens(String key) {
        Bucket bucket = buckets.get(key);
        return bucket == null ? maxTokens : bucket.availableTokens();
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    /**
     * 清理长时间空闲的桶（由 @Scheduled 定时任务调用）
     *
     * @return 清理的桶数量
     */
    public int cleanIdleBuckets() {
        Instant threshold = Instant.now().minus(maxIdle);
        int removed = 0;
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isIdle(threshold)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Cleaned {} idle buckets, {} remaining", removed, buckets.size());
        }
        return removed;
    }

    /**
     * 当前桶数量（用于监控）
     */
    public int bucketCount() {
        return buckets.size();
    }

    /**
     * 单个令牌桶（静态内部类，避免持有外部引用）
     */
    private static class Bucket {
        private final long maxTokens;
        private final double refillRate;
        private long tokens;
        private Instant lastRefillTime;
        private Instant lastAccessed;

        Bucket(long maxTokens, double refillRate) {
            this.maxTokens = maxTokens;
            this.refillRate = refillRate;
            this.tokens = maxTokens;
            this.lastRefillTime = Instant.now();
            this.lastAccessed = Instant.now();
        }

        private final ReentrantLock lock = new ReentrantLock();

        /**
         * 尝试消费令牌。ReentrantLock 保证 refill + consume 的原子性。
         */
        boolean tryConsume(long requested) {
            lock.lock();
            try {
                refill();
                lastAccessed = Instant.now();

                if (tokens < requested) {
                    return false;
                }
                tokens -= requested;
                return true;
            } finally {
                lock.unlock();
            }
        }

        /**
         * 查询可用令牌数。ReentrantLock 与 tryConsume 互斥。
         */
        long availableTokens() {
            lock.lock();
            try {
                refill();
                return tokens;
            } finally {
                lock.unlock();
            }
        }

        /**
         * 是否已空闲超过阈值
         */
        boolean isIdle(Instant threshold) {
            lock.lock();
            try {
                return lastAccessed.isBefore(threshold);
            } finally {
                lock.unlock();
            }
        }

        private void refill() {
            Instant now = Instant.now();
            // 防御 NTP 时钟回拨：负数时重置基准时间
            if (now.isBefore(lastRefillTime)) {
                log.warn("Clock moved backwards detected, resetting refill baseline");
                lastRefillTime = now;
                return;
            }
            double elapsedSeconds = Duration.between(lastRefillTime, now).toNanos() / 1_000_000_000.0;
            long tokensToAdd = (long) (elapsedSeconds * refillRate);

            if (tokensToAdd > 0) {
                tokens = Math.min(tokens + tokensToAdd, maxTokens);
                lastRefillTime = now;
            }
        }
    }
}
