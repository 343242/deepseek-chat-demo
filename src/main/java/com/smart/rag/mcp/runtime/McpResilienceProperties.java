package com.smart.rag.mcp.runtime;

import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 弹性配置（{@code mcp.resilience}）。
 * <p>
 * 仅承载三态熔断器参数（{@code mcp.resilience.circuit-breaker}），与 LLM 的 {@code app.llm.resilience}
 * 解耦——MCP 不依赖 {@code LlmConfig}（design D-1：retry 本期不做，故无 retry 段；后续通用化
 * {@code RetryPolicy} 后再补 {@code mcp.resilience.retry}）。
 */
@Component
@ConfigurationProperties(prefix = "mcp.resilience")
public class McpResilienceProperties {

    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties(null, null, null);

    /** 三态熔断器参数（默认值由 {@link CircuitBreakerProperties} compact constructor 兜底：5/30s/2）。 */
    public CircuitBreakerProperties getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerProperties circuitBreaker) {
        this.circuitBreaker = circuitBreaker == null
                ? new CircuitBreakerProperties(null, null, null)
                : circuitBreaker;
    }
}
