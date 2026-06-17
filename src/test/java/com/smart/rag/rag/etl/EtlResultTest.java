package com.smart.rag.rag.etl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EtlResult 单元测试。
 * <p>
 * 验证工厂方法的字段映射。
 */
class EtlResultTest {

    @Nested
    @DisplayName("success 工厂方法")
    class Success {

        @Test
        @DisplayName("生成 COMPLETED 状态的结果")
        void success_status() {
            EtlResult result = EtlResult.success(1L, 10);
            assertThat(result.documentId()).isEqualTo(1L);
            assertThat(result.status()).isEqualTo(EtlStatus.COMPLETED);
            assertThat(result.chunkCount()).isEqualTo(10);
            assertThat(result.errorMessage()).isNull();
        }

        @Test
        @DisplayName("chunkCount 可以为 0")
        void success_zeroChunks() {
            EtlResult result = EtlResult.success(2L, 0);
            assertThat(result.chunkCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("failed 工厂方法")
    class Failed {

        @Test
        @DisplayName("生成 FAILED 状态的结果")
        void failed_status() {
            EtlResult result = EtlResult.failed(3L, "解析错误");
            assertThat(result.documentId()).isEqualTo(3L);
            assertThat(result.status()).isEqualTo(EtlStatus.FAILED);
            assertThat(result.chunkCount()).isEqualTo(0);
            assertThat(result.errorMessage()).isEqualTo("解析错误");
        }
    }
}
