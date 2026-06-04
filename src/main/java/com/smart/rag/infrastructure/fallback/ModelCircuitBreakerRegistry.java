package com.smart.rag.infrastructure.fallback;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ModelCircuitBreakerRegistry {

    private final ModelCircuitBreakerProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, ModelCircuitBreaker> breakers = new ConcurrentHashMap<>();

    @Autowired
    public ModelCircuitBreakerRegistry(ModelCircuitBreakerProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ModelCircuitBreakerRegistry(ModelCircuitBreakerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isCallAllowed(String modelId) {
        if (!properties.isEnabled()) {
            return true;
        }
        return breaker(modelId).isCallAllowed(clock.millis());
    }

    public void recordSuccess(String modelId) {
        if (!properties.isEnabled()) {
            return;
        }
        breaker(modelId).recordSuccess();
    }

    public void recordFailure(String modelId) {
        if (!properties.isEnabled()) {
            return;
        }
        breaker(modelId).recordFailure(clock.millis());
    }

    public void releaseProbe(String modelId) {
        if (!properties.isEnabled()) {
            return;
        }
        ModelCircuitBreaker breaker = breakers.get(modelId);
        if (breaker != null) {
            breaker.releaseProbe();
        }
    }

    public CircuitBreakerState stateOf(String modelId) {
        if (!properties.isEnabled()) {
            return CircuitBreakerState.CLOSED;
        }
        return breaker(modelId).state(clock.millis());
    }

    private ModelCircuitBreaker breaker(String modelId) {
        return breakers.computeIfAbsent(modelId, ignored -> new ModelCircuitBreaker(
                properties.failureThreshold(),
                properties.cooldown(),
                properties.halfOpenMaxProbes()));
    }

    private static final class ModelCircuitBreaker {
        private final int failureThreshold;
        private final Duration cooldown;
        private final int halfOpenMaxProbes;

        private CircuitBreakerState state = CircuitBreakerState.CLOSED;
        private int failureCount;
        private int activeHalfOpenProbes;
        private long openedAtMs;

        private ModelCircuitBreaker(int failureThreshold, Duration cooldown, int halfOpenMaxProbes) {
            this.failureThreshold = failureThreshold;
            this.cooldown = cooldown;
            this.halfOpenMaxProbes = halfOpenMaxProbes;
        }

        synchronized boolean isCallAllowed(long nowMs) {
            refreshState(nowMs);
            if (state == CircuitBreakerState.OPEN) {
                return false;
            }
            if (state == CircuitBreakerState.HALF_OPEN) {
                if (activeHalfOpenProbes >= halfOpenMaxProbes) {
                    return false;
                }
                activeHalfOpenProbes++;
            }
            return true;
        }

        synchronized void recordSuccess() {
            failureCount = 0;
            activeHalfOpenProbes = 0;
            state = CircuitBreakerState.CLOSED;
        }

        synchronized void recordFailure(long nowMs) {
            if (state == CircuitBreakerState.HALF_OPEN) {
                open(nowMs);
                return;
            }
            failureCount++;
            if (failureCount >= failureThreshold) {
                open(nowMs);
            }
        }

        synchronized CircuitBreakerState state(long nowMs) {
            refreshState(nowMs);
            return state;
        }

        synchronized void releaseProbe() {
            if (state == CircuitBreakerState.HALF_OPEN && activeHalfOpenProbes > 0) {
                activeHalfOpenProbes--;
            }
        }

        private void refreshState(long nowMs) {
            if (state == CircuitBreakerState.OPEN
                    && nowMs - openedAtMs >= cooldown.toMillis()) {
                state = CircuitBreakerState.HALF_OPEN;
                activeHalfOpenProbes = 0;
            }
        }

        private void open(long nowMs) {
            state = CircuitBreakerState.OPEN;
            failureCount = failureThreshold;
            activeHalfOpenProbes = 0;
            openedAtMs = nowMs;
        }
    }
}
