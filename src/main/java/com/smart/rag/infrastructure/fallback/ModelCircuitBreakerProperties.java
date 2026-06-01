package com.smart.rag.infrastructure.fallback;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.chat.circuit-breaker")
public record ModelCircuitBreakerProperties(
        Boolean enabled,
        int failureThreshold,
        Duration cooldown,
        int halfOpenMaxProbes
) {

    public ModelCircuitBreakerProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (failureThreshold <= 0) {
            failureThreshold = 3;
        }
        if (cooldown == null || cooldown.isNegative() || cooldown.isZero()) {
            cooldown = Duration.ofSeconds(30);
        }
        if (halfOpenMaxProbes <= 0) {
            halfOpenMaxProbes = 1;
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
