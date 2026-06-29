package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpCircuitBreakerRegistry: per-ServerId 三态转换 + health 1:1 投影（§11.2 / §12-12）")
class McpCircuitBreakerRegistryTest {

    private static final String SERVER = "knowledge";

    /** 可控时钟（默认 failureThreshold=2/openDuration=30000/halfOpenMax=1，缩小阈值加速测试）。 */
    private static final class MutableClock extends Clock {
        long millis;
        MutableClock(long start) { this.millis = start; }
        void advance(long ms) { millis += ms; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static McpCircuitBreakerRegistry registry(MutableClock clock) {
        return new McpCircuitBreakerRegistry(new CircuitBreakerProperties(2, 30000L, 1), clock);
    }

    @Test
    @DisplayName("CLOSED：连续失败达阈值 → OPEN")
    void closed_to_open_onThreshold() {
        MutableClock clock = new MutableClock(0);
        McpCircuitBreakerRegistry reg = registry(clock);
        assertEquals(CircuitBreakerState.CLOSED, reg.stateOf(SERVER));
        assertTrue(reg.isCallAllowed(SERVER));

        reg.recordFailure(SERVER);
        assertEquals(CircuitBreakerState.CLOSED, reg.stateOf(SERVER)); // 1 < 2
        reg.recordFailure(SERVER);
        assertEquals(CircuitBreakerState.OPEN, reg.stateOf(SERVER)); // 2 >= 2
        assertFalse(reg.isCallAllowed(SERVER)); // OPEN 快速失败
    }

    @Test
    @DisplayName("OPEN：经 cool-down → HALF_OPEN；isCallAllowed 放行 1 次探测")
    void open_to_halfOpen_afterCooldown() {
        MutableClock clock = new MutableClock(0);
        McpCircuitBreakerRegistry reg = registry(clock);
        reg.recordFailure(SERVER);
        reg.recordFailure(SERVER);
        assertEquals(CircuitBreakerState.OPEN, reg.stateOf(SERVER));

        clock.advance(31_000); // > openDurationMs
        assertEquals(CircuitBreakerState.HALF_OPEN, reg.stateOf(SERVER));
        assertTrue(reg.isCallAllowed(SERVER)); // 第 1 次探测放行
        assertFalse(reg.isCallAllowed(SERVER)); // halfOpenMaxCalls=1 → 第 2 次拒绝
    }

    @Test
    @DisplayName("HALF_OPEN：探测成功（tryRecover）→ CLOSED（reset）")
    void halfOpen_to_closed_onProbeSuccess() {
        MutableClock clock = new MutableClock(0);
        McpCircuitBreakerRegistry reg = registry(clock);
        reg.recordFailure(SERVER);
        reg.recordFailure(SERVER);
        clock.advance(31_000);
        assertEquals(CircuitBreakerState.HALF_OPEN, reg.stateOf(SERVER));

        assertTrue(reg.tryRecoverFromHalfOpen(SERVER));
        assertEquals(CircuitBreakerState.CLOSED, reg.stateOf(SERVER));
    }

    @Test
    @DisplayName("HALF_OPEN：探测失败 → 回 OPEN（重计 cool-down）")
    void halfOpen_to_open_onProbeFailure() {
        MutableClock clock = new MutableClock(0);
        McpCircuitBreakerRegistry reg = registry(clock);
        reg.recordFailure(SERVER);
        reg.recordFailure(SERVER);
        clock.advance(31_000);
        assertEquals(CircuitBreakerState.HALF_OPEN, reg.stateOf(SERVER));

        reg.recordFailure(SERVER);
        assertEquals(CircuitBreakerState.OPEN, reg.stateOf(SERVER));
    }

    @Test
    @DisplayName("CLOSED：recordSuccess 不改变状态；per-key 隔离")
    void success_noop_and_perKeyIsolation() {
        MutableClock clock = new MutableClock(0);
        McpCircuitBreakerRegistry reg = registry(clock);
        reg.recordSuccess(SERVER);
        assertEquals(CircuitBreakerState.CLOSED, reg.stateOf(SERVER));

        reg.recordFailure(SERVER);
        reg.recordFailure(SERVER);
        assertEquals(CircuitBreakerState.OPEN, reg.stateOf(SERVER));
        // 另一个 key 不受影响
        assertEquals(CircuitBreakerState.CLOSED, reg.stateOf("other"));
        assertTrue(reg.isCallAllowed("other"));
    }
}
