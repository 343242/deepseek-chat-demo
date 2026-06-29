package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.llm.config.LlmConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * LLM 模型熔断器注册表 — 继承通用 {@link AbstractCircuitBreakerRegistry}，
 * 从 {@link LlmConfig} 解析熔断配置（{@code app.llm.resilience.circuit-breaker}）。
 * <p>
 * 保留此类（类名 / {@code @Component} / 构造签名 / 公共方法）以维持 LLM 侧所有既有
 * 调用方（{@code LlmClientFactory} / {@code LlmCircuitBreakerAdapterRegistry} /
 * {@code ProbeStreamHandler} 及对应测试）零改动；状态机与 per-key 管理逻辑已上提到
 * {@link AbstractCircuitBreakerRegistry} + {@link CircuitBreakerStateMachine}。
 * MCP 等其它远程调用域可各自继承同一基类，注入各自的 {@link CircuitBreakerProperties}。
 */
@Component
public class ModelCircuitBreakerRegistry extends AbstractCircuitBreakerRegistry {

    @Autowired
    public ModelCircuitBreakerRegistry(LlmConfig llmConfig) {
        super(llmConfig.resolveResilience().resolveCircuitBreaker(), Clock.systemUTC());
    }

    ModelCircuitBreakerRegistry(CircuitBreakerProperties properties, Clock clock) {
        super(properties, clock);
    }
}
