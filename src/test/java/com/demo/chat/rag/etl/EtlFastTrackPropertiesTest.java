package com.demo.chat.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EtlFastTrackProperties 单元测试。
 * <p>
 * 验证默认值、setter/getter、DataSize 解析。
 */
class EtlFastTrackPropertiesTest {

    @Nested
    @DisplayName("默认值")
    class Defaults {

        @Test
        @DisplayName("默认启用")
        void defaultEnabled() {
            EtlFastTrackProperties props = new EtlFastTrackProperties();
            assertThat(props.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("默认 maxDocCount = 10")
        void defaultMaxDocCount() {
            EtlFastTrackProperties props = new EtlFastTrackProperties();
            assertThat(props.getMaxDocCount()).isEqualTo(10);
        }

        @Test
        @DisplayName("默认 maxTotalSize = 5MB")
        void defaultMaxTotalSize() {
            EtlFastTrackProperties props = new EtlFastTrackProperties();
            assertThat(props.getMaxTotalSizeBytes()).isEqualTo(5 * 1024 * 1024);
        }
    }

    @Nested
    @DisplayName("自定义值")
    class CustomValues {

        @Test
        @DisplayName("禁用快速通道")
        void setEnabled_false() {
            EtlFastTrackProperties props = new EtlFastTrackProperties();
            props.setEnabled(false);
            assertThat(props.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("自定义 maxDocCount")
        void setMaxDocCount() {
            EtlFastTrackProperties props = new EtlFastTrackProperties();
            props.setMaxDocCount(5);
            assertThat(props.getMaxDocCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("自定义 maxTotalSize 支持 DataSize 格式")
        void setMaxTotalSize_dataSize() {
            EtlFastTrackProperties props = new EtlFastTrackProperties();
            props.setMaxTotalSize("10MB");
            assertThat(props.getMaxTotalSizeBytes()).isEqualTo(10 * 1024 * 1024);
        }

        @Test
        @DisplayName("maxTotalSize 支持 KB 单位")
        void setMaxTotalSize_kb() {
            EtlFastTrackProperties props = new EtlFastTrackProperties();
            props.setMaxTotalSize("512KB");
            assertThat(props.getMaxTotalSizeBytes()).isEqualTo(512 * 1024);
        }
    }
}
