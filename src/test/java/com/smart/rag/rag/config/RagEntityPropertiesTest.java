package com.smart.rag.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RagEntityProperties} 配置校验测试。
 * <p>
 * 覆盖 Wave 1 抽取字段默认值 + Wave 3 检索字段默认值 + α/β/γ 校验 + decay 校验。
 */
@DisplayName("RagEntityProperties")
class RagEntityPropertiesTest {

    @Nested
    @DisplayName("抽取字段默认值（Wave 1）")
    class ExtractionDefaultsTest {

        @Test
        @DisplayName("embeddingBatchSize/descriptionMaxLength 未配置回退默认")
        void extractionDefaults() {
            var p = new RagEntityProperties(
                    false, 0, 0,
                    0.85, 50, 20, 10, 1, 0.7,
                    0.5, 0.3, 0.2,
                    true, null, true
            );
            assertThat(p.embeddingBatchSize()).isEqualTo(10);
            assertThat(p.descriptionMaxLength()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("检索字段默认值（Wave 3，§7.1）")
    class RetrievalDefaultsTest {

        @Test
        @DisplayName("阈值/预算/topK/hops/decay 未配置（<=0）回退默认")
        void retrievalDefaults() {
            var p = new RagEntityProperties(
                    false, 0, 0,
                    0, 0, 0, -1, -1, 0,
                    0.5, 0.3, 0.2,
                    true, null, true
            );
            assertThat(p.matchThreshold()).isEqualTo(0.85);
            assertThat(p.frontierBudget()).isEqualTo(50);
            assertThat(p.chunkTopK()).isEqualTo(20);
            assertThat(p.expandChunkTopK()).isEqualTo(10);
            assertThat(p.expansionHops()).isEqualTo(1);
            assertThat(p.expansionDecay()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("expansionDecay <=0 或 >1 回退 0.7")
        void decayOutOfRange_fallsBackToDefault() {
            var tooLow = new RagEntityProperties(false, 0, 0, 0.85, 50, 20, 10, 1, 0.0, 0.5, 0.3, 0.2, true, null, true);
            assertThat(tooLow.expansionDecay()).isEqualTo(0.7);

            var tooHigh = new RagEntityProperties(false, 0, 0, 0.85, 50, 20, 10, 1, 1.5, 0.5, 0.3, 0.2, true, null, true);
            assertThat(tooHigh.expansionDecay()).isEqualTo(0.7);
        }
    }

    @Nested
    @DisplayName("α/β/γ 校验（§7.1 AC11）")
    class WeightValidationTest {

        @Test
        @DisplayName("α < 0 抛 IllegalArgumentException")
        void negativeAlpha_throws() {
            assertThatThrownBy(() -> new RagEntityProperties(
                    false, 10, 500, 0.85, 50, 20, 10, 1, 0.7,
                    -0.1, 0.3, 0.2, true, null, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("α/β/γ");
        }

        @Test
        @DisplayName("β < 0 抛 IllegalArgumentException")
        void negativeBeta_throws() {
            assertThatThrownBy(() -> new RagEntityProperties(
                    false, 10, 500, 0.85, 50, 20, 10, 1, 0.7,
                    0.5, -0.3, 0.2, true, null, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("α+β+γ == 0 抛 IllegalArgumentException（全为 0）")
        void allZero_throws() {
            assertThatThrownBy(() -> new RagEntityProperties(
                    false, 10, 500, 0.85, 50, 20, 10, 1, 0.7,
                    0, 0, 0, true, null, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能同时为 0");
        }
    }
}
