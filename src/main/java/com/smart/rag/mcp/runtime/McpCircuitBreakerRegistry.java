package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.fallback.AbstractCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * MCP server 熔断器注册表（per-{@code ServerId}）——继承通用 {@link AbstractCircuitBreakerRegistry}，
 * 从 {@link McpResilienceProperties}（{@code mcp.resilience.circuit-breaker}）解析参数。
 * <p>
 * 类比 {@code ModelCircuitBreakerRegistry}（LLM 侧，per-candidateId）；状态机逻辑上提到
 * {@code infrastructure.fallback} 通用件，本类只做 per-key 容器 + 参数注入。key = {@code ServerId.value()}。
 * <p>
 * {@link McpServerImpl} 直接经基类方法（{@code isCallAllowed/recordSuccess/recordFailure/stateOf}）
 * 驱动本注册表；A/B/C 计数过滤复用通用 {@code FallbackEligibility}（design D-4）。
 */
@Component
public class McpCircuitBreakerRegistry extends AbstractCircuitBreakerRegistry {

    @Autowired
    public McpCircuitBreakerRegistry(McpResilienceProperties properties) {
        super(properties.getCircuitBreaker(), Clock.systemUTC());
    }

    /** 测试构造：直接注入参数 + 时钟。 */
    McpCircuitBreakerRegistry(CircuitBreakerProperties properties, Clock clock) {
        super(properties, clock);
    }
}
