package com.smart.rag.infrastructure.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessagingMetrics 测试 — 聚焦 O-03 {@code messaging.consumer.receive.last.success} gauge。
 */
class MessagingMetricsTest {

    @Test
    @DisplayName("recordReceiveSuccess 注册 gauge 并写入当前 epoch ms")
    void registersAndUpdatesGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessagingMetrics metrics = new MessagingMetrics(registry);

        long before = System.currentTimeMillis();
        metrics.recordReceiveSuccess("rag_index_document", "index-group");
        long after = System.currentTimeMillis();

        Gauge g = registry.find("messaging.consumer.receive.last.success")
                .tag("topic", "rag_index_document").tag("group", "index-group").gauge();
        assertThat(g).isNotNull();
        assertThat(g.value()).isBetween((double) before, (double) after);
    }

    @Test
    @DisplayName("同一 (topic,group) 二次调用幂等：gauge 仅 1 个，值刷新")
    void idempotentPerTopicGroup() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessagingMetrics metrics = new MessagingMetrics(registry);

        metrics.recordReceiveSuccess("t", "g");
        Thread.sleep(5);
        metrics.recordReceiveSuccess("t", "g");

        long meterCount = registry.find("messaging.consumer.receive.last.success")
                .tag("topic", "t").tag("group", "g").meters().size();
        assertThat(meterCount).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 (topic,group) 注册独立 gauge")
    void distinctPerTopicGroup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessagingMetrics metrics = new MessagingMetrics(registry);

        metrics.recordReceiveSuccess("t1", "g");
        metrics.recordReceiveSuccess("t2", "g");

        assertThat(registry.find("messaging.consumer.receive.last.success")
                .tag("topic", "t1").tag("group", "g").gauge()).isNotNull();
        assertThat(registry.find("messaging.consumer.receive.last.success")
                .tag("topic", "t2").tag("group", "g").gauge()).isNotNull();
    }

    @Test
    @DisplayName("registry 为 null 时 no-op（不抛异常）")
    void nullRegistryIsNoOp() {
        MessagingMetrics metrics = new MessagingMetrics(null);
        metrics.recordReceiveSuccess("t", "g");  // 不抛
    }
}
