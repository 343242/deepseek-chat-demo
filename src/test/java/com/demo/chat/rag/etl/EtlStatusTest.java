package com.demo.chat.rag.etl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EtlStatus 枚举单元测试。
 * <p>
 * 验证枚举值与 code 映射。
 */
class EtlStatusTest {

    @Nested
    @DisplayName("code 映射")
    class CodeMapping {

        @Test
        @DisplayName("每个枚举值的 code 与 name 一致")
        void codeMatchesName() {
            for (EtlStatus status : EtlStatus.values()) {
                assertThat(status.getCode()).isEqualTo(status.name());
            }
        }

        @Test
        @DisplayName("COMPLETED 状态")
        void completed() {
            assertThat(EtlStatus.COMPLETED.getCode()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("FAILED 状态")
        void failed() {
            assertThat(EtlStatus.FAILED.getCode()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("VECTOR_FAILED 状态")
        void vectorFailed() {
            assertThat(EtlStatus.VECTOR_FAILED.getCode()).isEqualTo("VECTOR_FAILED");
        }
    }
}
