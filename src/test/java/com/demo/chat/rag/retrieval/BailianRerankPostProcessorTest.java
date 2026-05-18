package com.demo.chat.rag.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BailianRerankPostProcessor 单元测试
 * <p>
 * 由于 WebClient 在构造器中直接创建，无法方便地 mock 链式调用。
 * 因此重点测试：
 * 1. 空/null 输入处理
 * 2. isRetryable 方法（通过反射访问 private 方法）
 * 3. 通过 spy/反射验证 fallback 行为
 */
class BailianRerankPostProcessorTest {

    private BailianRerankPostProcessor processor;
    private Query query;

    @BeforeEach
    void setUp() {
        // 使用假 URL/API key，实际不会调用
        processor = new BailianRerankPostProcessor(
                "http://localhost:1", "fake-key", "test-model", 5);
        query = new Query("test query");
    }

    private Document doc(String id, String content) {
        return new Document(id, content, Map.of());
    }

    // ====================================================================
    // 空 / null 文档列表
    // ====================================================================

    @Nested
    @DisplayName("空 / null 文档列表")
    class EmptyOrNullInput {

        @Test
        @DisplayName("null 文档列表直接返回 null")
        void null_documents_returns_null() {
            List<Document> result = processor.process(query, null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("空文档列表直接返回空列表")
        void empty_documents_returns_empty() {
            List<Document> result = processor.process(query, List.of());
            assertThat(result).isEmpty();
        }
    }

    // ====================================================================
    // isRetryable 方法测试
    // ====================================================================

    @Nested
    @DisplayName("isRetryable 判断逻辑")
    class IsRetryableTest {

        private Method isRetryableMethod;

        @BeforeEach
        void setUp() throws Exception {
            isRetryableMethod = BailianRerankPostProcessor.class.getDeclaredMethod("isRetryable", Throwable.class);
            isRetryableMethod.setAccessible(true);
        }

        @Test
        @DisplayName("429 Too Many Requests 可重试")
        void retryable_on_429() throws Exception {
            var ex = WebClientResponseException.create(
                    429, "Too Many Requests", null, null, null);
            boolean result = (boolean) isRetryableMethod.invoke(processor, ex);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("503 Service Unavailable 可重试")
        void retryable_on_503() throws Exception {
            var ex = WebClientResponseException.create(
                    503, "Service Unavailable", null, null, null);
            boolean result = (boolean) isRetryableMethod.invoke(processor, ex);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("TimeoutException 可重试")
        void retryable_on_timeout() throws Exception {
            var ex = new TimeoutException("timed out");
            boolean result = (boolean) isRetryableMethod.invoke(processor, ex);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("ConnectException 可重试")
        void retryable_on_connect() throws Exception {
            var ex = new ConnectException("connection refused");
            boolean result = (boolean) isRetryableMethod.invoke(processor, ex);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("400 Bad Request 不可重试")
        void not_retryable_on_400() throws Exception {
            var ex = WebClientResponseException.create(
                    400, "Bad Request", null, null, null);
            boolean result = (boolean) isRetryableMethod.invoke(processor, ex);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("500 Internal Server Error 不可重试")
        void not_retryable_on_500() throws Exception {
            var ex = WebClientResponseException.create(
                    500, "Internal Server Error", null, null, null);
            boolean result = (boolean) isRetryableMethod.invoke(processor, ex);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("RuntimeException 不可重试")
        void not_retryable_on_runtime() throws Exception {
            var ex = new RuntimeException("some error");
            boolean result = (boolean) isRetryableMethod.invoke(processor, ex);
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("cause 是可重试异常时也可重试")
        void retryable_when_cause_is_retryable() throws Exception {
            var cause = new TimeoutException("timed out");
            var ex = new RuntimeException("wrapper", cause);
            boolean result = (boolean) isRetryableMethod.invoke(processor, ex);
            assertThat(result).isTrue();
        }
    }

    // ====================================================================
    // API 失败 → fallback
    // ====================================================================

    @Nested
    @DisplayName("API 失败 fallback 行为")
    class FallbackBehavior {

        @Test
        @DisplayName("API 调用失败时返回原始文档 + rerankFallback=true")
        void api_failure_returns_original_with_fallback() {
            // 连接 localhost:1 会立即失败
            var docs = List.of(
                    doc("d1", "content1"),
                    doc("d2", "content2"),
                    doc("d3", "content3")
            );

            List<Document> result = processor.process(query, docs);

            // 返回原始文档（顺序可能不变）
            assertThat(result).hasSize(3);
            assertThat(result.stream().map(Document::getId).toList())
                    .containsExactlyInAnyOrder("d1", "d2", "d3");

            // 所有文档都有 rerankFallback=true
            for (Document d : result) {
                assertThat(d.getMetadata()).containsEntry("rerankFallback", true);
            }
        }
    }
}
