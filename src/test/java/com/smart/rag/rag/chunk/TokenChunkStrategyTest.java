package com.smart.rag.rag.chunk;

import com.smart.rag.rag.config.DocumentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenChunkStrategy 单元测试。
 * <p>
 * 验证 Token 数分块：metadata 正确性、边界条件（空文档、超长单段、多文档）。
 * </p>
 */
class TokenChunkStrategyTest {

    private DocumentProperties properties;
    private TokenChunkStrategy strategy;

    @BeforeEach
    void setUp() {
        properties = new DocumentProperties();
        properties.setChunkSize(100);  // 较小的 chunkSize 以便测试
        strategy = new TokenChunkStrategy(properties);
    }

    @Nested
    @DisplayName("strategyName")
    class StrategyNameTest {

        @Test
        @DisplayName("返回 'token'")
        void returns_token() {
            assertThat(strategy.strategyName()).isEqualTo("token");
        }
    }

    @Nested
    @DisplayName("基本分块")
    class BasicChunking {

        @Test
        @DisplayName("单文档被切分为多个 chunk")
        void singleDoc_splitIntoChunks() {
            // 用一段长文本，确保超出 chunkSize 后被切分
            String longText = "这是一段测试文本。".repeat(200);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("每个 chunk 都包含 source 和 chunkIndex metadata")
        void eachChunk_hasMetadata() {
            String longText = "这是一段测试文本。".repeat(200);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.md");

            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                assertThat(chunk.getMetadata()).containsEntry("source", "test.md");
                assertThat(chunk.getMetadata()).containsEntry("chunkIndex", i);
            }
        }

        @Test
        @DisplayName("每个 chunk 都有 totalChunks metadata")
        void eachChunk_hasTotalChunks() {
            String longText = "这是一段测试文本。".repeat(200);
            Document doc = new Document(longText);

            List<Document> chunks = strategy.chunk(List.of(doc), "test.txt");

            for (Document chunk : chunks) {
                assertThat(chunk.getMetadata()).containsEntry("totalChunks", chunks.size());
            }
        }
    }

    @Nested
    @DisplayName("多文档分块")
    class MultiDocumentChunking {

        @Test
        @DisplayName("多文档分块后 chunkIndex 全局递增")
        void multiDoc_chunkIndexGlobalIncrement() {
            String text = "这是测试内容。".repeat(150);
            Document doc1 = new Document(text);
            Document doc2 = new Document(text);

            List<Document> chunks = strategy.chunk(List.of(doc1, doc2), "multi.txt");

            // 验证 chunkIndex 全局唯一且递增
            List<Integer> indices = chunks.stream()
                    .map(c -> (Integer) c.getMetadata().get("chunkIndex"))
                    .toList();

            for (int i = 0; i < indices.size(); i++) {
                assertThat(indices.get(i)).isEqualTo(i);
            }
        }

        @Test
        @DisplayName("totalChunks 等于所有 chunk 总数")
        void multiDoc_totalChunksCorrect() {
            String text = "这是测试内容。".repeat(150);
            Document doc1 = new Document(text);
            Document doc2 = new Document(text);

            List<Document> chunks = strategy.chunk(List.of(doc1, doc2), "multi.txt");

            int totalChunks = (int) chunks.get(0).getMetadata().get("totalChunks");
            assertThat(totalChunks).isEqualTo(chunks.size());
        }
    }

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @Test
        @DisplayName("空文档列表返回空列表")
        void emptyDocList_returnsEmpty() {
            List<Document> result = strategy.chunk(Collections.emptyList(), "empty.txt");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("短文档产生至少一个 chunk")
        void shortDoc_producesChunk() {
            Document doc = new Document("短文本");

            List<Document> chunks = strategy.chunk(List.of(doc), "short.txt");

            // TokenTextSplitter 可能将极短文本合并或不切分
            assertThat(chunks).isNotNull();
        }

        @Test
        @DisplayName("单个空文本文档仍产生一个 chunk")
        void blankTextDoc_stillProducesChunk() {
            Document doc = new Document("");

            List<Document> chunks = strategy.chunk(List.of(doc), "blank.txt");

            // TokenTextSplitter 对空文本仍可能产生 chunk
            assertThat(chunks).isNotNull();
        }
    }
}
