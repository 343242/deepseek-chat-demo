package com.demo.deepseekchat.advisor;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流器
 * <p>
 * 支持按 key（如 conversationId）独立限流。
 * 每个令牌桶独立维护：容量、当前令牌数、上次补充时间。
 */
public class TokenBucketLimiter implements RateLimiter {

    private final long maxTokens;
    private final double refillRate;
    private final Duration maxWait;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param maxTokens  桶容量（最大令牌数）
     * @param refillRate 每秒补充令牌数
     * @param maxWait    桶最大空闲时间，超时清理
     */
    public TokenBucketLimiter(long maxTokens, double refillRate, Duration maxWait) {
        this.maxTokens = maxTokens;
        this.refillRate = refillRate;
        this.maxWait = maxWait;
    }

    @Override
    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxTokens));
        return bucket.tryConsume(1);
    }

    /**
     * 获取指定 key 的剩余令牌数（用于监控）
     */
    public long getAvailableTokens(String key) {
        Bucket bucket = buckets.get(key);
        return bucket == null ? maxTokens : bucket.availableTokens();
    }

    /**
     * 清理长时间空闲的桶（可由定时任务调用）
     */
    public void cleanIdleBuckets() {
        Instant threshold = Instant.now().minus(maxWait);
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessed.isBefore(threshold));
    }

    /**
     * 单个令牌桶
     */
    private class Bucket {
        private final AtomicLong tokens;
        private volatile Instant lastRefillTime;
        private volatile Instant lastAccessed;

        Bucket(long initialTokens) {
            this.tokens = new AtomicLong(initialTokens);
            this.lastRefillTime = Instant.now();
            this.lastAccessed = Instant.now();
        }

        synchronized boolean tryConsume(long requested) {
            refill();
            lastAccessed = Instant.now();

            long current = tokens.get();
            if (current < requested) {
                return false;
            }
            tokens.set(current - requested);
            return true;
        }

        long availableTokens() {
            refill();
            return tokens.get();
        }

        private void refill() {
            Instant now = Instant.now();
            double elapsedSeconds = (now.toEpochMilli() - lastRefillTime.toEpochMilli()) / 1000.0;
            long tokensToAdd = (long) (elapsedSeconds * refillRate);

            if (tokensToAdd > 0) {
                long current = tokens.get();
                long newTokens = Math.min(current + tokensToAdd, maxTokens);
                tokens.set(newTokens);
                lastRefillTime = now;
            }
        }
    }
}
