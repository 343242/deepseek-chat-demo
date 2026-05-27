package com.smart.rag.rag.agent.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentEventPriority 枚举单元测试。
 * <p>
 * 验证 3 个枚举值的 getValue()、fromValue() 正向/降级行为。
 */
class AgentEventPriorityTest {

    @Nested
    @DisplayName("枚举值 getValue")
    class GetValue {

        @Test
        @DisplayName("CRITICAL 值为 1")
        void critical() {
            assertThat(AgentEventPriority.CRITICAL.getValue()).isEqualTo(1);
        }

        @Test
        @DisplayName("HIGH 值为 2")
        void high() {
            assertThat(AgentEventPriority.HIGH.getValue()).isEqualTo(2);
        }

        @Test
        @DisplayName("NORMAL 值为 3")
        void normal() {
            assertThat(AgentEventPriority.NORMAL.getValue()).isEqualTo(3);
        }

        @Test
        @DisplayName("枚举总数为 3")
        void valuesCount() {
            assertThat(AgentEventPriority.values()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("fromValue 正向解析")
    class FromValue {

        @Test
        @DisplayName("fromValue(1) == CRITICAL")
        void fromValue1_critical() {
            assertThat(AgentEventPriority.fromValue(1)).isEqualTo(AgentEventPriority.CRITICAL);
        }

        @Test
        @DisplayName("fromValue(2) == HIGH")
        void fromValue2_high() {
            assertThat(AgentEventPriority.fromValue(2)).isEqualTo(AgentEventPriority.HIGH);
        }

        @Test
        @DisplayName("fromValue(3) == NORMAL")
        void fromValue3_normal() {
            assertThat(AgentEventPriority.fromValue(3)).isEqualTo(AgentEventPriority.NORMAL);
        }
    }

    @Nested
    @DisplayName("fromValue 降级行为")
    class FromValueFallback {

        @Test
        @DisplayName("fromValue(99) 降级到 NORMAL")
        void fromValue99_fallsBackToNormal() {
            assertThat(AgentEventPriority.fromValue(99)).isEqualTo(AgentEventPriority.NORMAL);
        }

        @Test
        @DisplayName("fromValue(0) 降级到 NORMAL")
        void fromValue0_fallsBackToNormal() {
            assertThat(AgentEventPriority.fromValue(0)).isEqualTo(AgentEventPriority.NORMAL);
        }

        @Test
        @DisplayName("fromValue(-1) 降级到 NORMAL")
        void fromValueNegative_fallsBackToNormal() {
            assertThat(AgentEventPriority.fromValue(-1)).isEqualTo(AgentEventPriority.NORMAL);
        }

        @Test
        @DisplayName("fromValue(100) 降级到 NORMAL")
        void fromValueLarge_fallsBackToNormal() {
            assertThat(AgentEventPriority.fromValue(100)).isEqualTo(AgentEventPriority.NORMAL);
        }
    }
}
