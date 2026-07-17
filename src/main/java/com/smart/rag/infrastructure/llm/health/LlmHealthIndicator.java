package com.smart.rag.infrastructure.llm.health;

import com.smart.rag.infrastructure.fallback.CircuitBreakerState;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.infrastructure.llm.registry.RegistrySnapshot;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.LlmCircuitBreakerAdapterRegistry;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 子系统健康指标——聚合所有 candidate 的熔断器状态（{@link CircuitBreakerState} 只读投影），
 * 供 actuator {@code /health/llm} 消费。
 * <p>
 * LLM 是<b>可选</b>出站第三方子系统（与 {@code McpHealthIndicator} 同构）：<b>永不</b>把应用标 DOWN
 * （避免外部 LLM provider 故障触发编排重启风暴）——即使所有候选者熔断器全 OPEN，应用仍 UP + {@code allOpen=true}
 * detail，per-candidate 状态（含 OPEN）入 details。0 candidate（未配置）→ UP + {@code candidates: 0}。
 * <p>
 * <b>实现</b>：状态读取走 {@link LlmCircuitBreakerAdapterRegistry#getOrCreate} + {@link CircuitBreaker#getState}，
 * 与 {@code LlmMetrics#registerCircuitBreakerGauge} 共享同一稳定只读 API；{@code getOrCreate} 对已存在 candidate
 * 是幂等 no-op，不会创建新状态机。
 */
@Component
public class LlmHealthIndicator extends AbstractHealthIndicator {

    private final LlmClientRegistry clientRegistry;
    private final LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry;

    public LlmHealthIndicator(LlmClientRegistry clientRegistry,
                              LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry) {
        this.clientRegistry = clientRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        RegistrySnapshot snapshot = clientRegistry.snapshot();
        Map<String, CapabilityClient> clients = snapshot.clientsById();
        if (clients.isEmpty()) {
            builder.up().withDetail("candidates", 0);
            return;
        }

        int openCount = 0;
        int halfOpenCount = 0;
        for (Map.Entry<String, CapabilityClient> entry : clients.entrySet()) {
            String candidateId = entry.getKey();
            CapabilityClient client = entry.getValue();
            boolean disabled = snapshot.isDisabled(candidateId);
            CircuitBreakerState state = circuitBreakerRegistry.getOrCreate(candidateId).getState();
            if (state == CircuitBreakerState.OPEN) {
                openCount++;
            } else if (state == CircuitBreakerState.HALF_OPEN) {
                halfOpenCount++;
            }
            String detail = state.name()
                    + ": " + client.providerId() + "/" + client.modelName()
                    + (disabled ? " (disabled)" : "");
            builder.withDetail(candidateId, detail);
        }

        builder.withDetail("candidateCount", clients.size());
        builder.withDetail("disabledCount", snapshot.disabledSet().size());
        builder.withDetail("openCount", openCount);
        builder.withDetail("halfOpenCount", halfOpenCount);

        // LLM 可选：永不标应用 DOWN——全部 OPEN 仅入 detail，不击穿 liveness；
        // 流量调度应消费 per-candidate details 或熔断器 fallback chain 自身降级。
        if (openCount == clients.size()) {
            builder.up().withDetail("allOpen", true)
                    .withDetail("reason", "all LLM candidates are circuit-open (app stays UP: LLM is optional)");
        } else {
            builder.up();
        }
    }
}
