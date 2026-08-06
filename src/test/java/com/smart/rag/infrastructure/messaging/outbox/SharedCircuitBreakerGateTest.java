package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.MessageBusManagement;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SharedCircuitBreakerGate 测试（design §3.4）——共享 OPEN 熔断信号 + 2s 本地缓存 +
 * Redis 异常回退本地态（只调一次）+ broadcast try/catch 降级（P1-6.3）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharedCircuitBreakerGateTest {

    @Mock
    private RedissonClient redisson;

    @Mock
    private RBucket<Object> bucket;

    @Mock
    private ObjectProvider<MessageBusManagement> provider;

    @Mock
    private MessageBusManagement busManagement;

    private SharedCircuitBreakerGate gate;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);
        MessagingProperties properties = new MessagingProperties("T_", Duration.ofSeconds(30),
            null, null, null, null, null, null);
        when(redisson.getBucket("messaging:cb:chat_message_save")).thenReturn(bucket);
        gate = new SharedCircuitBreakerGate(redisson, provider, properties, clock);
    }

    @Test
    @DisplayName("isOpen：读 Redis bucket；本地缓存 2s 内第二次调用不再打 Redis")
    void isOpenCachesForTtl() {
        when(bucket.isExists()).thenReturn(true);
        assertThat(gate.isOpen("chat_message_save")).isTrue();
        assertThat(gate.isOpen("chat_message_save")).isTrue();   // 缓存命中
        verify(bucket, times(1)).isExists();

        // TTL 过期后重读
        clock = Clock.fixed(Instant.parse("2026-08-06T00:00:03Z"), ZoneOffset.UTC);
        gate = new SharedCircuitBreakerGate(redisson, provider,
            new MessagingProperties("T_", Duration.ofSeconds(30), null, null, null, null, null, null),
            clock);
        when(redisson.getBucket("messaging:cb:chat_message_save")).thenReturn(bucket);
        assertThat(gate.isOpen("chat_message_save")).isTrue();
        verify(bucket, times(2)).isExists();
    }

    @Test
    @DisplayName("isOpen：Redis 异常 → 回退本地 isCircuitBreakerOpen（只调一次）")
    void redisFailureFallsBackToLocal() {
        doThrow(new RuntimeException("redis down")).when(bucket).isExists();
        when(provider.getIfAvailable()).thenReturn(busManagement);
        when(busManagement.isCircuitBreakerOpen("chat_message_save")).thenReturn(true);

        assertThat(gate.isOpen("chat_message_save")).isTrue();
        verify(provider).getIfAvailable();   // 回退只调一次
        verify(busManagement).isCircuitBreakerOpen("chat_message_save");
    }

    @Test
    @DisplayName("isOpen：Redis 异常且本地 OPEN → 缓存后再次调用不回退（命中缓存）")
    void fallbackResultIsCached() {
        doThrow(new RuntimeException("redis down")).when(bucket).isExists();
        when(provider.getIfAvailable()).thenReturn(busManagement);
        when(busManagement.isCircuitBreakerOpen("chat_message_save")).thenReturn(true);

        assertThat(gate.isOpen("chat_message_save")).isTrue();
        assertThat(gate.isOpen("chat_message_save")).isTrue();
        verify(busManagement, times(1)).isCircuitBreakerOpen("chat_message_save");
    }

    @Test
    @DisplayName("isOpen：防御性二级回退——circuitBreakerState map 推导（provider 无 bean）")
    void stateMapSecondaryFallback() {
        doThrow(new RuntimeException("redis down")).when(bucket).isExists();
        when(provider.getIfAvailable()).thenReturn(null);

        // provider 无 bean → 无一级回退；无二级（management null）→ false
        assertThat(gate.isOpen("chat_message_save")).isFalse();
    }

    @Test
    @DisplayName("isOpen：provider 有 bean 但一级回退抛异常 → 二级 state map 推导")
    void stateMapFallbackAfterException() {
        doThrow(new RuntimeException("redis down")).when(bucket).isExists();
        when(provider.getIfAvailable()).thenReturn(busManagement);
        doThrow(new RuntimeException("management broke")).when(busManagement).isCircuitBreakerOpen(any());
        when(busManagement.circuitBreakerState()).thenReturn(Map.of("chat_message_save", "open"));

        assertThat(gate.isOpen("chat_message_save")).isTrue();
    }

    @Test
    @DisplayName("broadcastOpen：写 Redis（EX cooldown）+ 本地缓存立即 OPEN")
    void broadcastOpenWritesRedis() {
        gate.broadcastOpen("chat_message_save");
        verify(bucket).set("1", Duration.ofMillis(30000));
        // 本地缓存立即生效（不读 Redis）
        assertThat(gate.isOpen("chat_message_save")).isTrue();
        verify(bucket, never()).isExists();
    }

    @Test
    @DisplayName("broadcastClosed：删 Redis + 本地缓存立即 CLOSED")
    void broadcastClosedDeletesRedis() {
        gate.broadcastClosed("chat_message_save");
        verify(bucket).delete();
        assertThat(gate.isOpen("chat_message_save")).isFalse();
        verify(bucket, never()).isExists();
    }

    @Test
    @DisplayName("broadcastOpen/Closed：Redis 抛异常 → 仅本地缓存，不抛（P1-6.3）")
    void broadcastDegradesWithoutThrowing() {
        doThrow(new RuntimeException("redis down")).when(bucket).set(any(), any());
        assertThatCode(() -> gate.broadcastOpen("chat_message_save")).doesNotThrowAnyException();
        assertThat(gate.isOpen("chat_message_save")).isTrue();   // 本地缓存仍生效

        doThrow(new RuntimeException("redis down")).when(bucket).delete();
        assertThatCode(() -> gate.broadcastClosed("chat_message_save")).doesNotThrowAnyException();
        assertThat(gate.isOpen("chat_message_save")).isFalse();
    }

    @Test
    @DisplayName("Redisson null（未配置）→ isOpen 直接本地态，不 NPE")
    void noRedissonDegradesToLocal() {
        SharedCircuitBreakerGate localOnly = new SharedCircuitBreakerGate(null, provider,
            new MessagingProperties("T_", Duration.ofSeconds(30), null, null, null, null, null, null),
            clock);
        when(provider.getIfAvailable()).thenReturn(busManagement);
        when(busManagement.isCircuitBreakerOpen("chat_message_save")).thenReturn(false);

        assertThat(localOnly.isOpen("chat_message_save")).isFalse();
        // broadcast 不抛
        assertThatCode(() -> localOnly.broadcastOpen("chat_message_save")).doesNotThrowAnyException();
        assertThat(localOnly.isOpen("chat_message_save")).isTrue();   // 本地缓存
    }

    // ==================== P1-6.2：SendCircuitBreaker → gate 广播联动 ====================

    @Test
    @DisplayName("SendCircuitBreaker.tripOpen → gate.broadcastOpen(topic)（P1-6.2 冻结点）")
    void breakerTripBroadcastsOpen() {
        SharedCircuitBreakerGate gate = org.mockito.Mockito.mock(SharedCircuitBreakerGate.class);
        com.smart.rag.infrastructure.messaging.SendCircuitBreaker breaker =
            new com.smart.rag.infrastructure.messaging.SendCircuitBreaker(
                new MessagingProperties.CircuitBreakerConfig(2, 30000), clock, gate, "chat_message_save");

        breaker.recordFailure();
        breaker.recordFailure();   // 达到阈值 2 → trip OPEN

        verify(gate).broadcastOpen("chat_message_save");
    }

    @Test
    @DisplayName("SendCircuitBreaker HALF_OPEN→CLOSED → gate.broadcastClosed(topic)")
    void breakerCloseBroadcastsClosed() {
        SharedCircuitBreakerGate gate = org.mockito.Mockito.mock(SharedCircuitBreakerGate.class);
        MutableClock mc = new MutableClock(Instant.parse("2026-08-06T00:00:00Z"));
        com.smart.rag.infrastructure.messaging.SendCircuitBreaker breaker =
            new com.smart.rag.infrastructure.messaging.SendCircuitBreaker(
                new MessagingProperties.CircuitBreakerConfig(1, 30_000), mc, gate, "chat_message_save");

        breaker.recordFailure();   // trip OPEN
        assertThat(breaker.isCallAllowed()).isFalse();
        mc.advance(Duration.ofSeconds(31));   // cooldown 已过
        assertThat(breaker.isCallAllowed()).isTrue();   // OPEN→HALF_OPEN + 探测
        breaker.recordSuccess();   // HALF_OPEN probe 成功 → CLOSED → 广播

        verify(gate).broadcastClosed("chat_message_save");
    }

    /** 可推进的测试时钟（同一实例内模拟 cooldown 流逝）。 */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    @DisplayName("gate=null（无广播装配）→ trip/close 不 NPE（既有语义不变）")
    void breakerWithoutGateNoOps() {
        com.smart.rag.infrastructure.messaging.SendCircuitBreaker breaker =
            new com.smart.rag.infrastructure.messaging.SendCircuitBreaker(
                new MessagingProperties.CircuitBreakerConfig(1, 30_000), clock, null, null);
        assertThatCode(() -> {
            breaker.recordFailure();
            breaker.recordSuccess();
        }).doesNotThrowAnyException();
    }
}
