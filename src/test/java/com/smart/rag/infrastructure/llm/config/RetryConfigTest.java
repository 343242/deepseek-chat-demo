package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.exception.ClientException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RetryConfig 单元测试 — 覆盖 mergeWithOverride 合并语义与 effective*() 默认值。
 */
class RetryConfigTest {

    @Test
    void fullyNullConfig_returnsDefaultsFromEffectiveMethods() {
        RetryConfig config = new RetryConfig(null, null, null, null);
        assertThat(config.effectiveMaxAttempts()).isEqualTo(3);
        assertThat(config.effectiveBaseDelayMs()).isEqualTo(500L);
        assertThat(config.effectiveMaxDelayMs()).isEqualTo(5000L);
        assertThat(config.effectiveMultiplier()).isEqualTo(2.0);
    }

    @Test
    void mergeWithOverride_preservesBaseFieldsWhenOverrideIsNull() {
        RetryConfig base = new RetryConfig(5, 1000L, 10000L, 1.5);
        RetryConfig override = new RetryConfig(8, null, null, null);

        RetryConfig merged = base.mergeWithOverride(override);

        assertThat(merged.maxAttempts()).isEqualTo(8);
        assertThat(merged.baseDelayMs()).isEqualTo(1000L);
        assertThat(merged.maxDelayMs()).isEqualTo(10000L);
        assertThat(merged.multiplier()).isEqualTo(1.5);
    }

    @Test
    void mergeWithOverride_fullOverrideReplacesAllFields() {
        RetryConfig base = new RetryConfig(3, 500L, 5000L, 2.0);
        RetryConfig override = new RetryConfig(10, 200L, 2000L, 3.0);

        RetryConfig merged = base.mergeWithOverride(override);

        assertThat(merged.maxAttempts()).isEqualTo(10);
        assertThat(merged.baseDelayMs()).isEqualTo(200L);
        assertThat(merged.maxDelayMs()).isEqualTo(2000L);
        assertThat(merged.multiplier()).isEqualTo(3.0);
    }

    @Test
    void mergeWithOverride_withNullBaseFieldsTakesOverrideValues() {
        RetryConfig base = new RetryConfig(null, null, null, null);
        RetryConfig override = new RetryConfig(5, 800L, 8000L, 1.8);

        RetryConfig merged = base.mergeWithOverride(override);

        assertThat(merged.maxAttempts()).isEqualTo(5);
        assertThat(merged.baseDelayMs()).isEqualTo(800L);
        assertThat(merged.maxDelayMs()).isEqualTo(8000L);
        assertThat(merged.multiplier()).isEqualTo(1.8);
    }

    @Test
    void mergeWithOverride_bothNullProducesNullFieldsEffectiveDefaults() {
        RetryConfig base = new RetryConfig(null, null, null, null);
        RetryConfig override = new RetryConfig(null, null, null, null);

        RetryConfig merged = base.mergeWithOverride(override);

        assertThat(merged.maxAttempts()).isNull();
        assertThat(merged.baseDelayMs()).isNull();
        assertThat(merged.maxDelayMs()).isNull();
        assertThat(merged.multiplier()).isNull();
        assertThat(merged.effectiveMaxAttempts()).isEqualTo(3);
        assertThat(merged.effectiveBaseDelayMs()).isEqualTo(500L);
        assertThat(merged.effectiveMaxDelayMs()).isEqualTo(5000L);
        assertThat(merged.effectiveMultiplier()).isEqualTo(2.0);
    }

    @Test
    void compactConstructor_throwsOnNonPositiveValues() {
        assertThatThrownBy(() -> new RetryConfig(0, null, null, null))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("maxAttempts");

        assertThatThrownBy(() -> new RetryConfig(null, -1L, null, null))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("baseDelayMs");

        assertThatThrownBy(() -> new RetryConfig(null, null, 0L, null))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("maxDelayMs");

        assertThatThrownBy(() -> new RetryConfig(null, null, null, -0.5))
            .isInstanceOf(ClientException.class)
            .hasMessageContaining("multiplier");
    }

    @Test
    void compactConstructor_acceptsPositiveValues() {
        RetryConfig config = new RetryConfig(1, 1L, 1L, 0.1);
        assertThat(config.maxAttempts()).isEqualTo(1);
        assertThat(config.baseDelayMs()).isEqualTo(1L);
        assertThat(config.maxDelayMs()).isEqualTo(1L);
        assertThat(config.multiplier()).isEqualTo(0.1);
    }
}
