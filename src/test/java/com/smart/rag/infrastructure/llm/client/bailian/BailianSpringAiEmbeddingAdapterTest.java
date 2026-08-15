package com.smart.rag.infrastructure.llm.client.bailian;

import com.smart.rag.infrastructure.llm.EmbeddingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BailianSpringAiEmbeddingAdapter 单元测试
 * <p>
 * 核心断言：chunk 写库路径（embed(documents, options, batchingStrategy)）按 DOCUMENT 编码，
 * 查询侧（embed(String) / call）按 QUERY 编码——百炼非对称检索的官方契约。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BailianSpringAiEmbeddingAdapter 单元测试")
class BailianSpringAiEmbeddingAdapterTest {

    @Mock
    private BailianEmbeddingClient delegate;

    private BailianSpringAiEmbeddingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BailianSpringAiEmbeddingAdapter(delegate);
    }

    @Nested
    @DisplayName("Document 批量嵌入（PgVectorStore chunk 写库路径）")
    class DocumentBatchEmbedding {

        private final BatchingStrategy twoPerBatch = documents -> {
            List<List<Document>> batches = new ArrayList<>();
            for (int i = 0; i < documents.size(); i += 2) {
                batches.add(documents.subList(i, Math.min(i + 2, documents.size())));
            }
            return batches;
        };

        @Test
        @DisplayName("按 DOCUMENT 编码并遵循 BatchingStrategy 分批，结果按文档顺序拼接")
        void embedsDocumentsAsDocumentTypePreservingOrder() {
            List<Document> documents = List.of(
                new Document("chunk-0"), new Document("chunk-1"),
                new Document("chunk-2"), new Document("chunk-3"));
            float[] v0 = {0.1f};
            float[] v1 = {0.2f};
            float[] v2 = {0.3f};
            float[] v3 = {0.4f};
            when(delegate.embedBatch(List.of("chunk-0", "chunk-1"), EmbeddingType.DOCUMENT))
                .thenReturn(List.of(v0, v1));
            when(delegate.embedBatch(List.of("chunk-2", "chunk-3"), EmbeddingType.DOCUMENT))
                .thenReturn(List.of(v2, v3));

            List<float[]> result = adapter.embed(documents,
                EmbeddingOptions.builder().build(), twoPerBatch);

            assertThat(result).containsExactly(v0, v1, v2, v3);
            verify(delegate).embedBatch(List.of("chunk-0", "chunk-1"), EmbeddingType.DOCUMENT);
            verify(delegate).embedBatch(List.of("chunk-2", "chunk-3"), EmbeddingType.DOCUMENT);
            verify(delegate, never()).embedBatch(anyList(), eq(EmbeddingType.QUERY));
        }

        @Test
        @DisplayName("embedding 数量与文档数不一致时抛 IllegalStateException")
        void throwsOnSizeMismatch() {
            List<Document> documents = List.of(new Document("a"), new Document("b"));
            when(delegate.embedBatch(anyList(), eq(EmbeddingType.DOCUMENT)))
                .thenReturn(List.of(new float[] {0.1f}));

            assertThatThrownBy(() -> adapter.embed(documents,
                EmbeddingOptions.builder().build(), twoPerBatch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same number");
        }
    }

    @Nested
    @DisplayName("查询侧路由")
    class QuerySideRouting {

        @Test
        @DisplayName("embed(String) 按 QUERY 编码")
        void embedStringAsQuery() {
            float[] vector = {0.5f};
            when(delegate.embed("用户问题", EmbeddingType.QUERY)).thenReturn(vector);

            assertThat(adapter.embed("用户问题")).isSameAs(vector);
        }

        @Test
        @DisplayName("call(EmbeddingRequest) 按 QUERY 批量编码并保序映射")
        void callAsQueryBatch() {
            float[] v0 = {0.1f};
            float[] v1 = {0.2f};
            when(delegate.embedBatch(List.of("q1", "q2"), EmbeddingType.QUERY))
                .thenReturn(List.of(v0, v1));

            EmbeddingResponse response = adapter.call(
                new EmbeddingRequest(List.of("q1", "q2"), EmbeddingOptions.builder().build()));

            assertThat(response.getResults()).hasSize(2);
            assertThat(response.getResults().get(0).getOutput()).isSameAs(v0);
            assertThat(response.getResults().get(1).getOutput()).isSameAs(v1);
        }

        @Test
        @DisplayName("embed(List<String>) 走 call 路径即 QUERY 编码")
        void embedTextsListAsQuery() {
            float[] vector = {0.3f};
            when(delegate.embedBatch(List.of("q"), EmbeddingType.QUERY))
                .thenReturn(List.of(vector));

            assertThat(adapter.embed(List.of("q"))).containsExactly(vector);
        }
    }

    @Nested
    @DisplayName("单文档与元信息")
    class SingleDocumentAndMeta {

        @Test
        @DisplayName("embed(Document) 按 DOCUMENT 编码")
        void embedDocumentAsDocument() {
            float[] vector = {0.9f};
            when(delegate.embed("一个chunk", EmbeddingType.DOCUMENT)).thenReturn(vector);

            assertThat(adapter.embed(new Document("一个chunk"))).isSameAs(vector);
        }

        @Test
        @DisplayName("空白 Document 返回零向量且不调用底层客户端")
        void blankDocumentReturnsZeroVector() {
            when(delegate.dimension()).thenReturn(3);

            float[] result = adapter.embed(new Document("   "));

            assertThat(result).hasSize(3).containsOnly(0.0f);
            verify(delegate, never()).embed(anyString(), eq(EmbeddingType.DOCUMENT));
        }

        @Test
        @DisplayName("dimensions() 委托底层客户端")
        void dimensionsDelegates() {
            when(delegate.dimension()).thenReturn(2048);

            assertThat(adapter.dimensions()).isEqualTo(2048);
        }
    }
}
