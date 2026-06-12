package com.smart.rag.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
                    false,
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
                    false,
                    true, 0.5, 10, 0.5,
                    "deepseek/deepseek-chat", 0.2
            );
            assertThat(props.queryRewriteModel()).isEqualTo("deepseek/deepseek-chat");
            assertThat(props.queryRewriteTemperature()).isEqualTo(0.2);
        }
    }
}
