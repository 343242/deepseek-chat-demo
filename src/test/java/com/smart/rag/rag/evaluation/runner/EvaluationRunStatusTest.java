package com.smart.rag.rag.evaluation.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvaluationRunStatus 枚举转换测试
 */
class EvaluationRunStatusTest {

    @Nested
    @DisplayName("fromValue")
    class FromValue {

        @Test
        @DisplayName("pending")
        void pending() {
            assertThat(EvaluationRunStatus.fromValue("pending"))
                    .isEqualTo(EvaluationRunStatus.PENDING);
        }

        @Test
        @DisplayName("running")
        void running() {
            assertThat(EvaluationRunStatus.fromValue("running"))
                    .isEqualTo(EvaluationRunStatus.RUNNING);
        }

        @Test
        @DisplayName("completed")
        void completed() {
            assertThat(EvaluationRunStatus.fromValue("completed"))
                    .isEqualTo(EvaluationRunStatus.COMPLETED);
        }

        @Test
        @DisplayName("failed")
        void failed() {
            assertThat(EvaluationRunStatus.fromValue("failed"))
                    .isEqualTo(EvaluationRunStatus.FAILED);
        }

        @Test
        @DisplayName("非法值抛异常")
        void illegalValue() {
            assertThatThrownBy(() -> EvaluationRunStatus.fromValue("unknown"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unknown");
        }
    }

    @Nested
    @DisplayName("getValue")
    class GetValue {

        @Test
        @DisplayName("每个枚举值都有正确的 string")
        void values() {
            assertThat(EvaluationRunStatus.PENDING.getValue()).isEqualTo("pending");
            assertThat(EvaluationRunStatus.RUNNING.getValue()).isEqualTo("running");
            assertThat(EvaluationRunStatus.COMPLETED.getValue()).isEqualTo("completed");
            assertThat(EvaluationRunStatus.FAILED.getValue()).isEqualTo("failed");
        }
    }
}
