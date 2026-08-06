package com.smart.rag.infrastructure.messaging.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.lang.Nullable;

/**
 * Outbox 指标（R4）——registry 缺失时全部 no-op（与 MessagingMetrics 同风格）。
 * <ul>
 *   <li>{@code messaging.outbox.relay.delivered/failed/dead}：relay 投递 counter（按 topic）；</li>
 *   <li>{@code messaging.outbox.immediate.delivered/failed}：即时投递路径 counter（按 topic）；</li>
 *   <li>{@code messaging.outbox.pending} gauge（按 topic）、{@code messaging.outbox.oldest_age_seconds}
 *       gauge、{@code messaging.outbox.leader_active} gauge（由 OutboxRelay 注册）。</li>
 * </ul>
 */
public class OutboxMetrics {

    private final @Nullable MeterRegistry registry;

    public OutboxMetrics(@Nullable MeterRegistry registry) {
        this.registry = registry;
    }

    public void relayDelivered(String topic) {
        if (registry == null) return;
        registry.counter("messaging.outbox.relay.delivered", "topic", topic).increment();
    }

    public void relayFailed(String topic) {
        if (registry == null) return;
        registry.counter("messaging.outbox.relay.failed", "topic", topic).increment();
    }

    public void relayDead(String topic) {
        if (registry == null) return;
        registry.counter("messaging.outbox.relay.dead", "topic", topic).increment();
    }

    public void immediateDelivered(String topic) {
        if (registry == null) return;
        registry.counter("messaging.outbox.immediate.delivered", "topic", topic).increment();
    }

    public void immediateFailed(String topic) {
        if (registry == null) return;
        registry.counter("messaging.outbox.immediate.failed", "topic", topic).increment();
    }
}
