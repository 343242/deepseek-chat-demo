package com.smart.rag.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Per-topic send circuit breaker — three-state: CLOSED / OPEN / HALF_OPEN.
 * Follows project's ModelCircuitBreakerRegistry pattern.
 */
public class SendCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(SendCircuitBreaker.class);

    private final MessagingProperties.CircuitBreakerConfig config;
    private final Clock clock;

    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private int failureCount = 0;
    private long openedAtMs = 0;
    private int activeHalfOpenProbes = 0;

    SendCircuitBreaker(MessagingProperties.CircuitBreakerConfig config) {
        this(config, Clock.systemUTC());
    }

    SendCircuitBreaker(MessagingProperties.CircuitBreakerConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * Pre-send check: CLOSED allows, OPEN fast-fails, HALF_OPEN allows 1 probe.
     * Does NOT affect sendToDeadLetter() — DLQ must bypass circuit breaker.
     */
    synchronized boolean isCallAllowed() {
        refreshState();
        if (state == CircuitBreakerState.OPEN) {
            return false;
        }
        if (state == CircuitBreakerState.HALF_OPEN) {
            if (activeHalfOpenProbes >= 1) {
                return false;
            }
            activeHalfOpenProbes++;
        }
        return true;
    }

    synchronized void recordSuccess() {
        CircuitBreakerState prev = state;
        if (state == CircuitBreakerState.HALF_OPEN) {
            activeHalfOpenProbes--;
        }
        failureCount = 0;
        state = CircuitBreakerState.CLOSED;
        if (prev != CircuitBreakerState.CLOSED) {
            log.info("Circuit breaker transition: {} → CLOSED (probe succeeded)", prev);
        }
    }

    synchronized void recordFailure() {
        if (state == CircuitBreakerState.HALF_OPEN) {
            activeHalfOpenProbes--;
            tripOpen();
            return;
        }
        failureCount++;
        if (failureCount >= config.failureThreshold()) {
            tripOpen();
        }
    }

    synchronized CircuitBreakerState state() {
        refreshState();
        return state;
    }

    private void tripOpen() {
        log.warn("Circuit breaker tripped OPEN (failures={}/{}): will cooldown {}ms",
            failureCount, config.failureThreshold(), config.cooldownMillis());
        state = CircuitBreakerState.OPEN;
        openedAtMs = clock.millis();
        failureCount = config.failureThreshold();
    }

    private void refreshState() {
        if (state == CircuitBreakerState.OPEN
            && clock.millis() - openedAtMs >= config.cooldownMillis()) {
            log.info("Circuit breaker transition: OPEN → HALF_OPEN (cooldown elapsed)");
            state = CircuitBreakerState.HALF_OPEN;
        }
    }
}
