package com.smart.rag.infrastructure.fallback;

import java.time.Duration;

/**
 * 三态熔断器状态机（per-key 计数 + 状态转换）。
 * <p>
 * 与"模型"解耦的纯状态机：持有 {@code failureThreshold / cooldown / halfOpenMaxProbes}，
 * 实现 {@link CircuitBreakerState#CLOSED} → {@link CircuitBreakerState#OPEN} →
 * {@link CircuitBreakerState#HALF_OPEN} 三态转换。由 {@link AbstractCircuitBreakerRegistry}
 * per-key 持有，供 LLM、MCP 等所有远程调用弹性层复用。
 * <p>
 * <b>线程安全</b>：每个方法 {@code synchronized}（per-key 粒度，锁竞争仅限同一 key）。
 * <p>
 * <b>状态语义</b>（与 health 1:1，见 docs/MCP-CLIENT-INTEGRATION.md §11.2）：
 * <ul>
 *   <li>CLOSED：正常放行；连续失败 ≥ {@code failureThreshold} → OPEN</li>
 *   <li>OPEN：拒绝调用；经过 {@code cooldown} → HALF_OPEN</li>
 *   <li>HALF_OPEN：放行有限探测（≤ {@code halfOpenMaxProbes}）；探测成功 → CLOSED，探测失败 → OPEN</li>
 * </ul>
 * <p>
 * <b>恢复路径约束</b>：{@code recordSuccess} 在非 CLOSED 状态下不触发 CLOSED 转换；
 * HALF_OPEN → CLOSED 仅由 {@link #tryRecoverFromHalfOpen()} 触发。
 */
public final class CircuitBreakerStateMachine {

    private final int failureThreshold;
    private final Duration cooldown;
    private final int halfOpenMaxProbes;

    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private int failureCount;
    private int activeHalfOpenProbes;
    private long openedAtMs;

    public CircuitBreakerStateMachine(int failureThreshold, Duration cooldown, int halfOpenMaxProbes) {
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
        if (state == CircuitBreakerState.HALF_OPEN) {
            state = CircuitBreakerState.CLOSED;
        }
    }

    synchronized boolean tryRecoverFromHalfOpen() {
        if (state != CircuitBreakerState.HALF_OPEN) return false;
        state = CircuitBreakerState.CLOSED;
        failureCount = 0;
        activeHalfOpenProbes = 0;
        return true;
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
