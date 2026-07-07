package com.smart.rag.infrastructure.fallback;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 三态熔断器注册表基类（per-key 状态机管理）。
 * <p>
 * 与"模型"解耦：以通用 {@code key}（LLM 的 candidateId / MCP 的 ServerId）索引
 * {@link CircuitBreakerStateMachine}，配置由子类通过构造注入。
 * <p>
 * 复用关系：LLM 侧 {@link ModelCircuitBreakerRegistry}、MCP 侧熔断器注册表各自继承本类，
 * 注入各自的 {@link CircuitBreakerProperties}，共享同一套状态机实现（状态机逻辑上提到
 * {@link CircuitBreakerStateMachine}，本类只做 per-key 容器 + 时钟驱动）。
 * <p>
 * 状态语义见 {@link CircuitBreakerStateMachine}；与 health 的 1:1 投影见
 * docs/MCP-CLIENT-INTEGRATION.md §11.2。
 */
public abstract class AbstractCircuitBreakerRegistry {

    private final CircuitBreakerProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, CircuitBreakerStateMachine> breakers = new ConcurrentHashMap<>();

    protected AbstractCircuitBreakerRegistry(CircuitBreakerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isCallAllowed(String key) {
        return breaker(key).isCallAllowed(clock.millis());
    }

    public void recordSuccess(String key) {
        breaker(key).recordSuccess();
    }

    public void recordFailure(String key) {
        breaker(key).recordFailure(clock.millis());
    }

    public void releaseProbe(String key) {
        CircuitBreakerStateMachine breaker = breakers.get(key);
        if (breaker != null) {
            breaker.releaseProbe();
        }
    }

    public CircuitBreakerState stateOf(String key) {
        return breaker(key).state(clock.millis());
    }

    public boolean tryRecoverFromHalfOpen(String key) {
        return breaker(key).tryRecoverFromHalfOpen();
    }

    /**
     * 移除某 key 的熔断器状态（v4 McpServerRegistryAdmin.removeServer 用，防泄漏）。
     * <p>
     * 注意：被移除后再次访问该 key 会 lazy 重建（视为全新状态）。LLM 侧暂无调用方（model 配置不会动态删除）。
     */
    public void evict(String key) {
        breakers.remove(key);
    }

    private CircuitBreakerStateMachine breaker(String key) {
        return breakers.computeIfAbsent(key, ignored -> new CircuitBreakerStateMachine(
                properties.effectiveFailureThreshold(),
                Duration.ofMillis(properties.effectiveOpenDurationMs()),
                properties.effectiveHalfOpenMaxCalls()));
    }
}
