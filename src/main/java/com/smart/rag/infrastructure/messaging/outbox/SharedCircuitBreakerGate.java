package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.MessageBusManagement;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享 OPEN 熔断门控（design §3.4）——多实例共享"MQ 是否看似可用"信号。
 * <p>
 * 只共享 OPEN 信号，不重写状态机：某实例本地 {@code SendCircuitBreaker} trip OPEN 时经 Redis
 * 广播（{@code SET messaging:cb:{topic} 1 EX cooldown}，见 {@code broadcastOpen}），其它实例
 * 即时投递前读共享信号，OPEN 则跳过（避免对已挂 MQ 无效 send）。
 * <p>
 * 降级链：
 * <ol>
 *   <li>本地缓存 2s（热路径不每消息一次 Redis RTT；topic 集合有界，无泄漏）；</li>
 *   <li>Redis 异常/不可用 → 回退本实例 {@link MessageBusManagement#isCircuitBreakerOpen}（只调一次，
 *       parent 跨 child 契约）；防御性二级回退从 {@link MessageBusManagement#circuitBreakerState()} 推导；</li>
 *   <li>RedissonClient 缺失（未配置）→ 直接本地态。</li>
 * </ol>
 * {@code broadcastOpen/Closed} 先更新本地缓存、再 try/catch 写 Redis——Redis 挂时降级为仅本地
 * 缓存（P1-6.3），不破坏 send 链路。
 * <p>
 * 装配注意：{@code MessageBusManagement} 经 {@link ObjectProvider} 懒解析——gate 由 bus 装配点
 * 注入，bus 又实现 MessageBusManagement，直引会成构造循环。
 */
public class SharedCircuitBreakerGate {

    private static final Logger log = LoggerFactory.getLogger(SharedCircuitBreakerGate.class);

    private final @Nullable RedissonClient redisson;
    private final ObjectProvider<MessageBusManagement> busManagementProvider;
    private final String cbSignalPrefix;
    private final long cbLocalCacheTtlMs;
    private final long cooldownMillis;
    private final Clock clock;

    private final Map<String, CachedState> localCache = new ConcurrentHashMap<>();

    record CachedState(boolean open, long expiresAt) {}

    public SharedCircuitBreakerGate(@Nullable RedissonClient redisson,
                                    ObjectProvider<MessageBusManagement> busManagementProvider,
                                    MessagingProperties properties) {
        this(redisson, busManagementProvider, properties, Clock.systemUTC());
    }

    SharedCircuitBreakerGate(@Nullable RedissonClient redisson,
                             ObjectProvider<MessageBusManagement> busManagementProvider,
                             MessagingProperties properties,
                             Clock clock) {
        this.redisson = redisson;
        this.busManagementProvider = busManagementProvider;
        this.cbSignalPrefix = properties.outbox().cbSignalPrefix();
        this.cbLocalCacheTtlMs = properties.outbox().cbLocalCacheTtlMs();
        this.cooldownMillis = properties.circuitBreaker().cooldownMillis();
        this.clock = clock;
    }

    /**
     * topic 的 MQ 是否 OPEN（跳过即时投递）。本地缓存 2s 命中直接返回；miss 读 Redis；
     * Redis 异常回退本地熔断态（只调一次，评审 P2）。
     */
    public boolean isOpen(String topic) {
        long now = clock.millis();
        CachedState cached = localCache.get(topic);
        if (cached != null && cached.expiresAt() > now) {
            return cached.open();
        }
        boolean open;
        try {
            open = redisson != null && bucket(topic).isExists();
        } catch (Exception e) {
            open = fallbackLocal(topic);   // 回退只调一次
            log.debug("cb gate Redis read failed, fallback to local breaker: topic={}, err={}",
                topic, e.getMessage());
        }
        localCache.put(topic, new CachedState(open, now + cbLocalCacheTtlMs));
        return open;
    }

    /** 本实例熔断 trip OPEN → 广播（P1-6.2，由 SendCircuitBreaker.tripOpen 触发）。 */
    public void broadcastOpen(String topic) {
        // 先更新本地缓存，再 try/catch 写 Redis（P1-6.3：Redis 挂时降级为仅本地缓存，不抛）
        localCache.put(topic, new CachedState(true, clock.millis() + cbLocalCacheTtlMs));
        if (redisson == null) {
            return;
        }
        try {
            bucket(topic).set("1", Duration.ofMillis(cooldownMillis));
        } catch (Exception e) {
            log.warn("broadcastOpen fallback to local cache only: topic={}, err={}", topic, e.getMessage());
        }
    }

    /** HALF_OPEN 探测成功 → CLOSED 时广播清除信号（P1-6.2，由 SendCircuitBreaker.recordSuccess 触发）。 */
    public void broadcastClosed(String topic) {
        localCache.put(topic, new CachedState(false, clock.millis() + cbLocalCacheTtlMs));
        if (redisson == null) {
            return;
        }
        try {
            bucket(topic).delete();
        } catch (Exception e) {
            log.warn("broadcastClosed fallback to local cache only: topic={}, err={}", topic, e.getMessage());
        }
    }

    private RBucket<Object> bucket(String topic) {
        return redisson.getBucket(cbSignalPrefix + topic);
    }

    /** 回退本实例本地熔断态：优先 isCircuitBreakerOpen（parent 契约），防御性从 state map 推导。 */
    private boolean fallbackLocal(String topic) {
        MessageBusManagement management = busManagementProvider.getIfAvailable();
        if (management != null) {
            try {
                return management.isCircuitBreakerOpen(topic);
            } catch (Exception e) {
                log.debug("cb gate local fallback failed: topic={}, err={}", topic, e.getMessage());
            }
        }
        if (management != null) {
            String state = management.circuitBreakerState().get(topic);
            return "open".equals(state);
        }
        return false;
    }
}
