package com.smart.rag.infrastructure.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MessagingHealthIndicator}.
 * <p>
 * Phase D D-2/D-3: 移除了 legacy DLQ 相关断言（{@code legacyDlqSize} detail 随 DLQ 退役）。
 * 覆盖 producer / subscriptions / circuit-breaker 的 UP/DOWN 语义。
 */
class MessagingHealthIndicatorTest {

    private MessageBusManagement busManagement;
    private MessagingHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        busManagement = mock(MessageBusManagement.class);
        indicator = new MessagingHealthIndicator(busManagement);
    }

    @Test
    void producerUnreachable_reportsDown() {
        when(busManagement.isProducerHealthy()).thenReturn(false);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("producer", "unreachable");
    }

    @Test
    void noActiveSubscriptions_reportsUpWithWarning() {
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(0);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("subscriptions", "none");
    }

    @Test
    void openCircuitBreaker_reportsDown() {
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(2);
        Map<String, String> breakers = new LinkedHashMap<>();
        breakers.put("chat_message_save", "open");
        when(busManagement.circuitBreakerState()).thenReturn(breakers);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("circuitBreaker", breakers);
    }

    @Test
    void allHealthy_reportsUp() {
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(3);
        when(busManagement.circuitBreakerState()).thenReturn(Map.of("chat_message_save", "closed"));

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("producer", "healthy");
        assertThat(health.getDetails()).containsEntry("activeSubscriptions", 3);
    }

    @Test
    void halfOpenCircuitBreaker_reportsDown() {
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(1);
        when(busManagement.circuitBreakerState()).thenReturn(Map.of("t", "half_open"));

        assertThat(health().getStatus()).isEqualTo(Status.DOWN);
    }

    private Health health() {
        Health.Builder builder = new Health.Builder();
        try {
            indicator.doHealthCheck(builder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return builder.build();
    }
}
