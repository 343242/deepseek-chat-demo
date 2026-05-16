package com.demo.chat.rag.retrieval;

import com.demo.chat.rag.config.RagRetrievalProperties;
import com.demo.chat.rag.mapper.VectorStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HybridDocumentRetriever 单元测试
 */
@ExtendWith(MockitoExtension.class)
class HybridDocumentRetrieverTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private VectorStoreMapper vectorStoreMapper;

    private QueryNormalizer queryNormalizer;

    // Record 字段顺序（与源码一致）:
    // queryRewriteEnabled, hybridRetrievalEnabled, ftsConfig, vectorTopK, bm25TopK,
    // rrfK, rerankEnabled, rerankBaseUrl, rerankApiKey, rerankModel, rerankTopN,
    // mmrEnabled, mmrLambda, mmrTopK, similarityThreshold

    private static RagRetrievalProperties defaultProperties() {
        return new RagRetrievalProperties(
                false,  // queryRewriteEnabled
                true,   // hybridRetrievalEnabled
                "jiebacfg",
                10,     // vectorTopK
                10,     // bm25TopK
                60,     // rrfK
                false,  // rerankEnabled
                "https://example.com", // rerankBaseUrl
                null,   // rerankApiKey (disabled)
                "qwen3-rerank",
                5,      // rerankTopN
                false,  // mmrEnabled
                0.7,    // mmrLambda
                5,      // mmrTopK
                0.0     // similarityThreshold
        );
    }

    private static RagRetrievalProperties vectorOnlyProperties() {
        return new RagRetrievalProperties(
                false,  // queryRewriteEnabled
                false,  // hybridRetrievalEnabled
                "jiebacfg",
                10, 10, 60,
                false, "https://example.com", null, "qwen3-rerank", 5,
                false, 0.7, 5, 0.0
        );
    }

    @BeforeEach
    void setUp() {
        queryNormalizer = new QueryNormalizer();
    }

    private HybridDocumentRetriever createRetriever(RagRetrievalProperties props, Long userId, Long teamId) {
        return new HybridDocumentRetriever(vectorStore, vectorStoreMapper, props, queryNormalizer, userId, teamId);
    }

    private Document doc(String id, String content) {
        return new Document(id, content, Map.of());
    }

    private Query query(String text) {
        return new Query(text);
    }

    // ====================================================================
    // 纯向量检索模式
    // ====================================================================

    @Nested
    @DisplayName("纯向量检索模式 (hybridRetrievalEnabled=false)")
    class VectorOnlyMode {

        @Test
        @DisplayName("只调用向量检索，不调用 BM25")
        void vector_only_should_not_call_bm25() {
            var props = vectorOnlyProperties();
            var retriever = createRetriever(props, 1L, null);
            var docs = List.of(doc("d1", "hello"), doc("d2", "world"));

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(docs);

            List<Document> result = retriever.retrieve(query("test"));

            assertThat(result).hasSize(2);
            verify(vectorStore).similaritySearch(any(SearchRequest.class));
            verifyNoInteractions(vectorStoreMapper);
        }

        @Test
        @DisplayName("向量检索返回空列表时不报错")
        void vector_only_empty_results() {
            var props = vectorOnlyProperties();
            var retriever = createRetriever(props, 1L, null);

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            List<Document> result = retriever.retrieve(query("test"));

            assertThat(result).isEmpty();
        }
    }

    // ====================================================================
    // 混合检索 + RRF 融合
    // ====================================================================

    @Nested
    @DisplayName("混合检索 + RRF 融合")
    class HybridMode {

        @Test
        @DisplayName("向量 + BM25 两路都有结果时 RRF 融合")
        void hybrid_rrf_fusion_both_sources() {
            var props = defaultProperties();
            var retriever = createRetriever(props, 1L, null);

            var vDoc1 = doc("d1", "content1");
            var vDoc2 = doc("d2", "content2");
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(vDoc1, vDoc2));

            var bDoc2 = doc("d2", "content2b");
            var bDoc3 = doc("d3", "content3");
            when(vectorStoreMapper.bm25Search(eq("jiebacfg"), eq("test"), eq("userId"), eq("1"), eq(10)))
                    .thenReturn(List.of(bDoc2, bDoc3));

            List<Document> result = retriever.retrieve(query("test"));

            assertThat(result).isNotEmpty();
            assertThat(result.stream().map(Document::getId).toList()).contains("d2", "d1", "d3");
        }

        @Test
        @DisplayName("向量有结果 BM25 无结果")
        void hybrid_vector_only_no_bm25() {
            var props = defaultProperties();
            var retriever = createRetriever(props, 1L, null);

            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc("d1", "c1")));
            when(vectorStoreMapper.bm25Search(any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            List<Document> result = retriever.retrieve(query("test"));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("d1");
        }

        @Test
        @DisplayName("BM25 失败时优雅降级返回向量结果")
        void bm25_failure_degrades_gracefully() {
            var props = defaultProperties();
            var retriever = createRetriever(props, 1L, null);

            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc("d1", "c1"), doc("d2", "c2")));
            when(vectorStoreMapper.bm25Search(any(), any(), any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("DB error"));

            List<Document> result = retriever.retrieve(query("test"));

            assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ====================================================================
    // 团队隔离
    // ====================================================================

    @Nested
    @DisplayName("团队隔离")
    class TeamIsolation {

        @Test
        @DisplayName("teamId 非空时 BM25 按 teamId 过滤")
        void team_isolation_uses_teamId() {
            var props = defaultProperties();
            var retriever = createRetriever(props, 1L, 99L);

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            when(vectorStoreMapper.bm25Search(eq("jiebacfg"), eq("test"), eq("teamId"), eq("99"), eq(10)))
                    .thenReturn(List.of());

            retriever.retrieve(query("test"));

            verify(vectorStoreMapper).bm25Search("jiebacfg", "test", "teamId", "99", 10);
        }

        @Test
        @DisplayName("teamId 为 null 时 BM25 按 userId 过滤")
        void no_team_uses_userId() {
            var props = defaultProperties();
            var retriever = createRetriever(props, 42L, null);

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            when(vectorStoreMapper.bm25Search(eq("jiebacfg"), eq("test"), eq("userId"), eq("42"), eq(10)))
                    .thenReturn(List.of());

            retriever.retrieve(query("test"));

            verify(vectorStoreMapper).bm25Search("jiebacfg", "test", "userId", "42", 10);
        }
    }

    // ====================================================================
    // sanitizeQuery 验证
    // ====================================================================

    @Nested
    @DisplayName("sanitizeQuery 间接验证")
    class SanitizeQueryTest {

        @Test
        @DisplayName("含 tsquery 运算符的查询仍能正常检索")
        void query_with_tsquery_operators() {
            var props = defaultProperties();
            var retriever = createRetriever(props, 1L, null);

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            when(vectorStoreMapper.bm25Search(eq("jiebacfg"), eq("test   query   other"), eq("userId"), eq("1"), eq(10)))
                    .thenReturn(List.of());

            retriever.retrieve(query("test & query | other"));

            verify(vectorStoreMapper).bm25Search(eq("jiebacfg"), eq("test   query   other"), any(), any(), anyInt());
        }

        @Test
        @DisplayName("Unicode 引号被替换为空格")
        void unicode_quotes_replaced() {
            var props = defaultProperties();
            var retriever = createRetriever(props, 1L, null);

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
            // \u201C\u201D → each replaced with space, so "\u201Chello\u201D \u201Cworld\u201D" → " hello   world " → trim → "hello   world"
            when(vectorStoreMapper.bm25Search(eq("jiebacfg"), eq("hello   world"), eq("userId"), eq("1"), eq(10)))
                    .thenReturn(List.of());

            retriever.retrieve(query("\u201Chello\u201D \u201Cworld\u201D"));

            verify(vectorStoreMapper).bm25Search(eq("jiebacfg"), eq("hello   world"), any(), any(), anyInt());
        }

        @Test
        @DisplayName("纯特殊字符查询 → sanitize 后为空 → BM25 不被调用")
        void only_special_chars_no_bm25() {
            var props = defaultProperties();
            var retriever = createRetriever(props, 1L, null);

            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            retriever.retrieve(query("&&||!!"));

            verifyNoInteractions(vectorStoreMapper);
        }
    }
}
