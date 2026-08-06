package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingProperties;

import java.util.concurrent.ThreadLocalRandom;

/**
 * pollLoop 连接级失败退避重连（design §3 Redis 故障韧性）。
 * <ul>
 *   <li>指数退避 + 封顶：{@code initial=1s, multiplier=2, max=30s}——避免 Redis 恢复后瞬时大量重连压垮；</li>
 *   <li>jitter ±20%：每次 sleep 加随机偏移，防止多 consumer/多实例同步重连形成"重连风暴"；</li>
 *   <li>成功即重置：任何一次成功的 XREADGROUP（含空拉取）→ reset() 回初始值。</li>
 * </ul>
 * 退避期间消息不丢（PEL / retry-zset 兜底）；sleep 只阻塞 poll 线程自身，不影响业务线程。
 */
class ReconnectBackoff {

    private final long initialMs;
    private final long maxMs;
    private final long jitterRangeMs;
    private long currentMs;

    ReconnectBackoff(MessagingProperties.ReconnectBackoffConfig config) {
        this.initialMs = config.initialMs();
        this.maxMs = config.maxMs();
        this.jitterRangeMs = (long) (config.initialMs() * config.jitterFactor());
        this.currentMs = initialMs;
    }

    /** 下一次失败后的 sleep 时长（含 ±20% jitter），并推进退避指数。 */
    synchronized long nextSleepMs() {
        long base = currentMs;
        currentMs = Math.min(Math.max(currentMs * (long) 2, currentMs), maxMs);
        long jitter = jitterRangeMs > 0
            ? ThreadLocalRandom.current().nextLong(-jitterRangeMs, jitterRangeMs + 1)
            : 0;
        return Math.max(1, base + jitter);
    }

    /** 任何一次成功（含空拉取）后复位。 */
    synchronized void reset() {
        currentMs = initialMs;
    }
}
