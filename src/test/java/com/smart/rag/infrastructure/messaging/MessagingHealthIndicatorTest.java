package com.smart.rag.infrastructure.messaging;

import com.smart.rag.chat.service.MessageDeadLetterQueue;
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
 * Verifies the Phase C Step 3 addition of the {@code legacyDlqSize} detail
 * without changing existing UP/DOWN semantics.
 */
class MessagingHealthIndicatorTest {

    private MessageBusManagement busManagement;
    private MessageDeadLetterQueue deadLetterQueue;
    private MessagingHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        busManagement = mock(MessageBusManagement.class);
        deadLetterQueue = mock(MessageDeadLetterQueue.class);
        indicator = new MessagingHealthIndicator(busManagement, deadLetterQueue);
    }

    @Test
    void producerUnreachable_reportsDownAndExposesLegacyDlqSize() {
        when(busManagement.isProducerHealthy()).thenReturn(false);
        when(deadLetterQueue.size()).thenReturn(42L);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("producer", "unreachable");
        assertThat(health.getDetails()).containsEntry("legacyDlqSize", 42L);
    }

    @Test
    void noActiveSubscriptions_reportsUpWithWarningAndLegacyDlqSize() {
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(0);
        when(deadLetterQueue.size()).thenReturn(0L);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("subscriptions", "none");
        assertThat(health.getDetails()).containsEntry("legacyDlqSize", 0L);
    }

    @Test
    void openCircuitBreaker_reportsDownAndExposesLegacyDlqSize() {
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(2);
        Map<String, String> breakers = new LinkedHashMap<>();
        breakers.put("chat_message_save", "open");
        when(busManagement.circuitBreakerState()).thenReturn(breakers);
        when(deadLetterQueue.size()).thenReturn(5L);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("circuitBreaker", breakers);
        assertThat(health.getDetails()).containsEntry("legacyDlqSize", 5L);
    }

    @Test
    void allHealthy_reportsUpAndExposesLegacyDlqSize() {
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(3);
        when(busManagement.circuitBreakerState()).thenReturn(Map.of("chat_message_save", "closed"));
        when(deadLetterQueue.size()).thenReturn(11L);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("producer", "healthy");
        assertThat(health.getDetails()).containsEntry("activeSubscriptions", 3);
        assertThat(health.getDetails()).containsEntry("legacyDlqSize", 11L);
    }

    @Test
    void halfOpenCircuitBreaker_reportsDown() {
        // Regression: half_open must still trigger DOWN (existing behavior unchanged)
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(1);
        when(busManagement.circuitBreakerState()).thenReturn(Map.of("t", "half_open"));
        when(deadLetterQueue.size()).thenReturn(0L);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("legacyDlqSize", 0L);
    }

    @Test
    void sustainedNonZeroLegacyDlq_doesNotFlipDownWhenOtherwiseHealthy() {
        // Phase C Step 3 D3: sustained non-zero size is NOT unhealthy (scheduler drains)
        when(busManagement.isProducerHealthy()).thenReturn(true);
        when(busManagement.activeSubscriptionCount()).thenReturn(2);
        when(busManagement.circuitBreakerState()).thenReturn(Map.of());
        when(deadLetterQueue.size()).thenReturn(999L);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("legacyDlqSize", 999L);
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
