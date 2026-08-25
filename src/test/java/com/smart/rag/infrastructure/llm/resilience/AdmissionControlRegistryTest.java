package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.config.ConcurrencyConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdmissionControlRegistry 复用/替换/evict（WS4 决策 18/19，AC10）")
class AdmissionControlRegistryTest {

    @Test
    @DisplayName("同 candidateId 且 config 全等 → 复用同一实例（refresh 无超发窗口）")
    void sameConfigReusesInstance() {
        AdmissionControlRegistry registry = new AdmissionControlRegistry(null);
        ConcurrencyConfig config = new ConcurrencyConfig(8, 1000L);

        AdmissionControl a = registry.getOrCreate("c1", config);
        AdmissionControl b = registry.getOrCreate("c1", config);

        assertThat(a).isSameAs(b);
    }

    @Test
    @DisplayName("maxConcurrent 变化 → 替换新实例")
    void maxConcurrentChangeReplaces() {
        AdmissionControlRegistry registry = new AdmissionControlRegistry(null);
        AdmissionControl a = registry.getOrCreate("c1", new ConcurrencyConfig(8, 1000L));
        AdmissionControl b = registry.getOrCreate("c1", new ConcurrencyConfig(16, 1000L));

        assertThat(b).isNotSameAs(a);
    }

    @Test
    @DisplayName("acquireTimeoutMs 变化（仅超时）→ 也替换（决策 18：仅比较 maxConcurrent 会静默不生效）")
    void timeoutOnlyChangeAlsoReplaces() {
        AdmissionControlRegistry registry = new AdmissionControlRegistry(null);
        AdmissionControl a = registry.getOrCreate("c1", new ConcurrencyConfig(8, 1000L));
        AdmissionControl b = registry.getOrCreate("c1", new ConcurrencyConfig(8, 5000L));

        assertThat(b).isNotSameAs(a);
    }

    @Test
    @DisplayName("maxConcurrent <= 0 → DISABLED 单例且不注册")
    void disabledWhenZero() {
        AdmissionControlRegistry registry = new AdmissionControlRegistry(null);
        AdmissionControl a = registry.getOrCreate("c1", new ConcurrencyConfig(0, 1000L));

        assertThat(a).isSameAs(AdmissionControl.DISABLED);
        assertThat(registry.contains("c1")).isFalse();
    }

    @Test
    @DisplayName("evict：条目移除 + inflight gauge 从 MeterRegistry 移除（无僵尸序列）")
    void evictRemovesGauge() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(meterRegistry);
        AdmissionControlRegistry registry = new AdmissionControlRegistry(metrics);

        registry.getOrCreate("c1", new ConcurrencyConfig(4, 1000L));
        assertThat(meterRegistry.find("llm.inflight").tag("candidateId", "c1").meter()).isNotNull();

        registry.evict("c1");
        assertThat(registry.contains("c1")).isFalse();
        assertThat(meterRegistry.find("llm.inflight").tag("candidateId", "c1").meter()).isNull();

        // evict 后可重新创建并重新注册 gauge
        AdmissionControl recreated = registry.getOrCreate("c1", new ConcurrencyConfig(4, 1000L));
        assertThat(recreated).isNotSameAs(AdmissionControl.DISABLED);
        assertThat(meterRegistry.find("llm.inflight").tag("candidateId", "c1").meter()).isNotNull();
    }
}
