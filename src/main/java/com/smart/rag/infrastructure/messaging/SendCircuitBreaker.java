package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.messaging.MessagingCircuitBreakerState;
import com.smart.rag.infrastructure.messaging.outbox.SharedCircuitBreakerGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Clock;

/**
 * Per-topic send circuit breaker — three-state: CLOSED / OPEN / HALF_OPEN.
 * Follows project's ModelCircuitBreakerRegistry pattern.
 * <p>
 * P1-6.2（child 2 跨 child 契约）：构造器可注入 {@link SharedCircuitBreakerGate}（nullable 防循环
 * 依赖/测试隔离），{@code tripOpen()} 广播 OPEN、{@code recordSuccess()} 从 HALF_OPEN→CLOSED 广播
 * CLOSED——经 Redis 同步到其它实例（gate 内部 try/catch 降级，不破坏 send 链路）。
 */
public class SendCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(SendCircuitBreaker.class);

    private final MessagingProperties.CircuitBreakerConfig config;
    private final Clock clock;
    private final @Nullable SharedCircuitBreakerGate gate;
    private final @Nullable String topic;

    private MessagingCircuitBreakerState state = MessagingCircuitBreakerState.CLOSED;
    private int failureCount = 0;
    private long openedAtMs = 0;
    private int activeHalfOpenProbes = 0;

    public SendCircuitBreaker(MessagingProperties.CircuitBreakerConfig config) {
        this(config, Clock.systemUTC());
    }

    public SendCircuitBreaker(MessagingProperties.CircuitBreakerConfig config, Clock clock) {
        this(config, clock, null, null);
    }

    /** 广播钩子构造（P1-6.2）：gate 为 null 时 no-op（既有语义不变）。 */
    public SendCircuitBreaker(MessagingProperties.CircuitBreakerConfig config, Clock clock,
                              @Nullable SharedCircuitBreakerGate gate, @Nullable String topic) {
        this.config = config;
        this.clock = clock;
        this.gate = gate;
        this.topic = topic;
    }

    /**
     * Pre-send check: CLOSED allows, OPEN fast-fails, HALF_OPEN allows 1 probe.
     * Does NOT affect sendToDeadLetter() — DLQ must bypass circuit breaker.
     */
    public synchronized boolean isCallAllowed() {
        refreshState();
        if (state == MessagingCircuitBreakerState.OPEN) {
            return false;
        }
        if (state == MessagingCircuitBreakerState.HALF_OPEN) {
            if (activeHalfOpenProbes >= 1) {
                return false;
            }
            activeHalfOpenProbes++;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        MessagingCircuitBreakerState prev = state;
        if (state == MessagingCircuitBreakerState.HALF_OPEN) {
            activeHalfOpenProbes--;
        }
        failureCount = 0;
        state = MessagingCircuitBreakerState.CLOSED;
        if (prev != MessagingCircuitBreakerState.CLOSED) {
            log.info("Circuit breaker transition: {} → CLOSED (probe succeeded)", prev);
        }
        // P1-6.2：HALF_OPEN→CLOSED 时广播共享 CLOSED 信号（gate 内部 try/catch 降级）
        if (gate != null && prev == MessagingCircuitBreakerState.HALF_OPEN && topic != null) {
            gate.broadcastClosed(topic);
        }
    }

    public synchronized void recordFailure() {
        if (state == MessagingCircuitBreakerState.HALF_OPEN) {
            activeHalfOpenProbes--;
            tripOpen();
            return;
        }
        failureCount++;
        if (failureCount >= config.failureThreshold()) {
            tripOpen();
        }
    }

    public synchronized MessagingCircuitBreakerState state() {
        refreshState();
        return state;
    }

    private void tripOpen() {
        log.warn("Circuit breaker tripped OPEN (failures={}/{}): will cooldown {}ms",
            failureCount, config.failureThreshold(), config.cooldownMillis());
        state = MessagingCircuitBreakerState.OPEN;
        openedAtMs = clock.millis();
        failureCount = config.failureThreshold();
        // P1-6.2：trip OPEN 时广播共享 OPEN 信号（其它实例即时投递前跳过；gate 内部 try/catch 降级）
        if (gate != null && topic != null) {
            gate.broadcastOpen(topic);
        }
    }

    private void refreshState() {
        if (state == MessagingCircuitBreakerState.OPEN
            && clock.millis() - openedAtMs >= config.cooldownMillis()) {
            log.info("Circuit breaker transition: OPEN → HALF_OPEN (cooldown elapsed)");
            state = MessagingCircuitBreakerState.HALF_OPEN;
        }
    }
}
