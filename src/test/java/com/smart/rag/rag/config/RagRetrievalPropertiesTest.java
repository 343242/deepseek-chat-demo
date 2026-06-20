package com.smart.rag.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RagRetrievalProperties")
class RagRetrievalPropertiesTest {

    @Nested
    @DisplayName("queryRewrite 字段")
    class QueryRewriteFieldsTest {

        @Test
        @DisplayName("默认 queryRewriteModel 为 null")
        void defaultModelIsNull() {
            var props = new RagRetrievalProperties(
                    true, true, "jiebacfg",
                    30, 30, 60,
                    60,
                    false,
                    20,
                    true, 0.5, 10, 0.5,
                    null, null
            );
            assertThat(props.queryRewriteModel()).isNull();
            assertThat(props.queryRewriteTemperature()).isNull();
        }

        @Test
        @DisplayName("自定义 model 和 temperature")
        void customModelAndTemperature() {
            var props = new RagRetrievalProperties(
                    true, true, "jiebacfg",
                    30, 30, 60,
                    60,
                    false,
                    20,
                    true, 0.5, 10, 0.5,
                    "deepseek/deepseek-chat", 0.2
            );
            assertThat(props.queryRewriteModel()).isEqualTo("deepseek/deepseek-chat");
            assertThat(props.queryRewriteTemperature()).isEqualTo(0.2);
        }
    }

    @Nested
    @DisplayName("候选池校验（rerankTopN vs mmrTopK）")
    class CandidatePoolValidationTest {

        @Test
        @DisplayName("rerankTopN <= mmrTopK 时构造抛异常（避免调换顺序后 MMR 退化为 no-op）")
        void rerankTopNMustBeGreaterThanMmrTopK() {
            assertThatThrownBy(() -> new RagRetrievalProperties(
                    true, true, "jiebacfg",
                    30, 30, 60,
                    60,
                    true,
                    5,
                    true, 0.7, 10, 0.5,
                    null, null
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("rerankTopN must be > mmrTopK");
        }

        @Test
        @DisplayName("rerankTopN 未配置（<=0）回退默认 20")
        void rerankTopNDefaultsTo20WhenUnset() {
            var props = new RagRetrievalProperties(
                    true, true, "jiebacfg",
                    30, 30, 60,
                    60,
                    false,
                    0,
                    false, 0.7, 5, 0.5,
                    null, null
            );
            assertThat(props.rerankTopN()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("召回约束（fusionTopK vs rerankTopN）")
    class FusionTopKValidationTest {

        @Test
        @DisplayName("fusionTopK < rerankTopN 时构造抛异常（Rerank 候选不足）")
        void fusionTopKMustBeAtLeastRerankTopN() {
            assertThatThrownBy(() -> new RagRetrievalProperties(
                    true, true, "jiebacfg",
                    30, 30, 60,
                    15,
                    true,
                    20,
                    true, 0.7, 10, 0.5,
                    null, null
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("fusionTopK must be >= rerankTopN");
        }

        @Test
        @DisplayName("fusionTopK 未配置（<=0）回退默认 60")
        void fusionTopKDefaultsTo60WhenUnset() {
            var props = new RagRetrievalProperties(
                    true, true, "jiebacfg",
                    30, 30, 60,
                    0,
                    false,
                    20,
                    false, 0.7, 5, 0.5,
                    null, null
            );
            assertThat(props.fusionTopK()).isEqualTo(60);
        }
    }
}
