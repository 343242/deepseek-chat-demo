package com.smart.rag.agent.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.workspace.RetrievedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolResult 单元测试。
 * <p>
 * 验证 success/failure 工厂方法、toJson 序列化、内容截断、@Deprecated 无参 toJson。
 */
class ToolResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("success() 工厂方法")
    class SuccessFactory {

        @Test
        @DisplayName("创建成功结果")
        void success_createsResult() {
            ToolResult result = ToolResult.success("search", "found 3 docs", null, 100L);

            assertThat(result.success()).isTrue();
            assertThat(result.action()).isEqualTo("search");
            assertThat(result.summary()).isEqualTo("found 3 docs");
            assertThat(result.documents()).isNull();
            assertThat(result.durationMs()).isEqualTo(100L);
            assertThat(result.errorMessage()).isNull();
            assertThat(result.errorCategory()).isNull();
        }

        @Test
        @DisplayName("创建带文档的成功结果")
        void success_withDocuments() {
            List<RetrievedDocument> docs = List.of(
                    new RetrievedDocument("doc1", "content", 0.95, "search", 0, Map.of())
            );

            ToolResult result = ToolResult.success("search", "found docs", docs, 200L);

            assertThat(result.success()).isTrue();
            assertThat(result.documents()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("failure() 工厂方法")
    class FailureFactory {

        @Test
        @DisplayName("创建失败结果")
        void failure_createsResult() {
            ToolResult result = ToolResult.failure("search", "timeout", "API_ERROR", 500L);

            assertThat(result.success()).isFalse();
            assertThat(result.action()).isEqualTo("search");
            assertThat(result.errorMessage()).isEqualTo("timeout");
            assertThat(result.errorCategory()).isEqualTo("API_ERROR");
            assertThat(result.durationMs()).isEqualTo(500L);
            assertThat(result.summary()).isNull();
            assertThat(result.documents()).isNull();
        }
    }

    @Nested
    @DisplayName("toJson(ObjectMapper)")
    class ToJsonWithMapper {

        @Test
        @DisplayName("成功结果包含 success=true, action, summary")
        void successResult_containsExpectedFields() throws Exception {
            ToolResult result = ToolResult.success("search", "found docs", null, 100L);
            String json = result.toJson(objectMapper);

            assertThat(json).contains("\"success\":true");
            assertThat(json).contains("\"action\":\"search\"");
            assertThat(json).contains("\"summary\":\"found docs\"");
            assertThat(json).contains("\"durationMs\":100");
        }

        @Test
        @DisplayName("失败结果包含 success=false, errorMessage, errorCategory")
        void failureResult_containsExpectedFields() {
            ToolResult result = ToolResult.failure("search", "timeout", "API_ERROR", 500L);
            String json = result.toJson(objectMapper);

            assertThat(json).contains("\"success\":false");
            assertThat(json).contains("\"errorMessage\":\"timeout\"");
            assertThat(json).contains("\"errorCategory\":\"API_ERROR\"");
        }

        @Test
        @DisplayName("documents 为 null 时不包含 documents 字段")
        void nullDocuments_noDocumentsField() {
            ToolResult result = ToolResult.success("search", "ok", null, 50L);
            String json = result.toJson(objectMapper);

            assertThat(json).doesNotContain("\"documents\"");
            assertThat(json).doesNotContain("\"documentCount\"");
        }

        @Test
        @DisplayName("documents 为空列表时不包含 documents 字段")
        void emptyDocuments_noDocumentsField() {
            ToolResult result = ToolResult.success("search", "ok", Collections.emptyList(), 50L);
            String json = result.toJson(objectMapper);

            assertThat(json).doesNotContain("\"documents\"");
            assertThat(json).doesNotContain("\"documentCount\"");
        }

        @Test
        @DisplayName("documents 非空时包含 documentCount 和 documents")
        void nonEmptyDocuments_containsDocumentsField() {
            List<RetrievedDocument> docs = List.of(
                    new RetrievedDocument("d1", "content1", 0.9, "search", 0, Map.of()),
                    new RetrievedDocument("d2", "content2", 0.8, "search", 1, Map.of())
            );
            ToolResult result = ToolResult.success("search", "ok", docs, 100L);
            String json = result.toJson(objectMapper);

            assertThat(json).contains("\"documentCount\":2");
            assertThat(json).contains("\"documents\"");
        }

        @Test
        @DisplayName("文档内容超过 500 字符时截断")
        void longContent_truncated() {
            String longContent = "a".repeat(600);
            List<RetrievedDocument> docs = List.of(
                    new RetrievedDocument("d1", longContent, 0.9, "search", 0, Map.of())
            );
            ToolResult result = ToolResult.success("search", "ok", docs, 100L);
            String json = result.toJson(objectMapper);

            // 截断后的内容包含 "..." 后缀
            assertThat(json).contains("a...\"");
            // 不应包含完整的 600 个字符
            assertThat(json).doesNotContain("a".repeat(600));
        }

        @Test
        @DisplayName("输出为有效 JSON")
        void outputIsValidJson() throws Exception {
            ToolResult result = ToolResult.success("search", "ok", null, 50L);
            String json = result.toJson(objectMapper);

            // ObjectMapper 能成功解析回来
            Object parsed = objectMapper.readValue(json, Object.class);
            assertThat(parsed).isNotNull();
        }
    }

    @Nested
    @DisplayName("@Deprecated 无参 toJson()")
    class DeprecatedToJson {

        @Test
        @DisplayName("无参 toJson() 仍然工作")
        @SuppressWarnings("deprecation")
        void deprecated_toJson_stillWorks() {
            ToolResult result = ToolResult.success("search", "ok", null, 50L);
            String json = result.toJson();

            assertThat(json).contains("\"success\":true");
            assertThat(json).contains("\"action\":\"search\"");
        }
    }
}
