package com.demo.chat.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonExtractor 单元测试
 * <p>
 * 验证三层容错策略：raw JSON → markdown code block → 嵌入式 JSON
 */
class JsonExtractorTest {

    @Nested
    @DisplayName("第一层：Raw JSON")
    class RawJson {

        @Test
        @DisplayName("直接返回 raw object")
        void rawObject() {
            String json = "{\"key\": \"value\"}";
            assertThat(JsonExtractor.extractJson(json)).isEqualTo(json);
        }

        @Test
        @DisplayName("直接返回 raw array")
        void rawArray() {
            String json = "[1, 2, 3]";
            assertThat(JsonExtractor.extractJson(json)).isEqualTo(json);
        }

        @Test
        @DisplayName("带前导空格")
        void leadingWhitespace() {
            String json = "  {\"key\": \"value\"}";
            assertThat(JsonExtractor.extractJson(json)).isEqualTo("{\"key\": \"value\"}");
        }
    }

    @Nested
    @DisplayName("第二层：Markdown code block")
    class MarkdownCodeBlock {

        @Test
        @DisplayName("提取 ```json ... ```")
        void extractMarkdownJson() {
            String input = "Here is the result:\n```json\n{\"score\": 0.9}\n```\nDone.";
            assertThat(JsonExtractor.extractJson(input)).isEqualTo("{\"score\": 0.9}");
        }

        @Test
        @DisplayName("提取 ```json ... ``` array")
        void extractMarkdownArray() {
            String input = "```json\n[{\"a\": 1}, {\"b\": 2}]\n```";
            assertThat(JsonExtractor.extractJson(input)).isEqualTo("[{\"a\": 1}, {\"b\": 2}]");
        }
    }

    @Nested
    @DisplayName("第三层：嵌入式 JSON")
    class EmbeddedJson {

        @Test
        @DisplayName("提取嵌入的 { }")
        void extractEmbeddedObject() {
            String input = "The result is {\"key\": \"value\"} as expected";
            assertThat(JsonExtractor.extractJson(input)).isEqualTo("{\"key\": \"value\"}");
        }

        @Test
        @DisplayName("提取嵌入的 [ ]")
        void extractEmbeddedArray() {
            String input = "Scores are [0.8, 0.9, 0.7] overall";
            assertThat(JsonExtractor.extractJson(input)).isEqualTo("[0.8, 0.9, 0.7]");
        }

        @Test
        @DisplayName("优先提取 { } 而非 [ ]")
        void preferObjectOverArray() {
            String input = "data {\"a\": 1} and [1, 2]";
            assertThat(JsonExtractor.extractJson(input)).isEqualTo("{\"a\": 1}");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null 输入")
        void nullInput() {
            assertThat(JsonExtractor.extractJson(null)).isEqualTo("");
        }

        @Test
        @DisplayName("空字符串")
        void emptyInput() {
            assertThat(JsonExtractor.extractJson("")).isEqualTo("");
        }

        @Test
        @DisplayName("纯文本无 JSON")
        void noJsonAtAll() {
            assertThat(JsonExtractor.extractJson("just plain text")).isEqualTo("just plain text");
        }
    }
}
