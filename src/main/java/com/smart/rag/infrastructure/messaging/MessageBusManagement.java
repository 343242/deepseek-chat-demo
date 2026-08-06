package com.smart.rag.infrastructure.messaging;

import java.util.Map;

/**
 * Message bus management interface — ops and health check only.
 * Separated from {@link MessageBus} SPI to avoid business code depending on management methods.
 */
public interface MessageBusManagement {

    /** Check producer connectivity (checks internal state, does NOT send probe messages) */
    boolean isProducerHealthy();

    /** Active subscription count */
    int activeSubscriptionCount();

    /** Circuit breaker state per topic: topic → state name (closed/open/half_open) */
    Map<String, String> circuitBreakerState();

    /**
     * Per-topic send circuit breaker open check（R7，child 2 SharedCircuitBreakerGate 读——
     * Redis 共享信号不可用时回退本实例本地熔断态）。
     */
    boolean isCircuitBreakerOpen(String topic);
}
