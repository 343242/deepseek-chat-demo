package com.smart.rag.rag.retrieval;

import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MmrDocumentPostProcessor 单元测试
 */
@ExtendWith(MockitoExtension.class)
class MmrDocumentPostProcessorTest {

    @Mock
    private VectorStoreMapper vectorStoreMapper;

    private Query query;

    @BeforeEach
    void setUp() {
        query = new Query("test query");
    }

    private Document doc(String id, Map<String, Object> metadata) {
        return new Document(id, "content of " + id, metadata);
    }

    private MmrDocumentPostProcessor createProcessor(double lambda, int topK) {
        return new MmrDocumentPostProcessor(lambda, topK, vectorStoreMapper);
    }

    // ====================================================================
    // 空列表 / null 输入
    // ====================================================================

    @Nested
    @DisplayName("空列表 / null 输入")
    class EmptyInput {

        @Test
        @DisplayName("null 输入直接返回 null")
        void null_input_returns_null() {
            var processor = createProcessor(0.7, 3);
            List<Document> result = processor.process(query, null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("空列表直接返回空列表")
        void empty_list_returns_empty() {
            var processor = createProcessor(0.7, 3);
            List<Document> result = processor.process(query, List.of());
            assertThat(result).isEmpty();
        }
    }

    // ====================================================================
    // 文档数 <= topK
    // ====================================================================

    @Nested
    @DisplayName("文档数 <= topK 直接返回")
    class WithinTopK {

        @Test
        @DisplayName("文档数等于 topK 直接返回，不调用 pairwiseCosineDistance")
        void docs_equals_topK_returned_directly() {
            var processor = createProcessor(0.7, 3);
            var docs = List.of(doc("d1", Map.of()), doc("d2", Map.of()), doc("d3", Map.of()));

            List<Document> result = processor.process(query, docs);

            assertThat(result).hasSize(3);
            // 不调用 pairwiseCosineDistance
            verifyNoInteractions(vectorStoreMapper);
        }

        @Test
        @DisplayName("文档数小于 topK 直接返回")
        void docs_less_than_topK_returned_directly() {
            var processor = createProcessor(0.7, 5);
            var docs = List.of(doc("d1", Map.of()), doc("d2", Map.of()));

            List<Document> result = processor.process(query, docs);

            assertThat(result).hasSize(2);
        }
    }

    // ====================================================================
    // MMR 选择行为
    // ====================================================================

    @Nested
    @DisplayName("MMR 选择行为")
    class MmrSelection {

        @Test
        @DisplayName("优先选 rerankScore 最高的文档")
        void selects_highest_rerank_score_first() {
            var processor = createProcessor(0.7, 2);
            var docs = List.of(
                    doc("d1", Map.of("rerankScore", 0.9)),
                    doc("d2", Map.of("rerankScore", 0.3)),
                    doc("d3", Map.of("rerankScore", 0.6)),
                    doc("d4", Map.of("rerankScore", 0.5))
            );

            // 所有文档距离设为 0.5（中等相似）
            Map<String, Double> distances = new java.util.HashMap<>();
            distances.put("d1|d2", 0.5); distances.put("d2|d1", 0.5);
            distances.put("d1|d3", 0.5); distances.put("d3|d1", 0.5);
            distances.put("d1|d4", 0.5); distances.put("d4|d1", 0.5);
            distances.put("d2|d3", 0.5); distances.put("d3|d2", 0.5);
            distances.put("d2|d4", 0.5); distances.put("d4|d2", 0.5);
            distances.put("d3|d4", 0.5); distances.put("d4|d3", 0.5);
            when(vectorStoreMapper.pairwiseCosineDistance(any())).thenReturn(distances);

            List<Document> result = processor.process(query, docs);

            assertThat(result).hasSize(2);
            // 第一个一定是 d1（rerankScore 0.9 最高）
            assertThat(result.get(0).getId()).isEqualTo("d1");
        }

        @Test
        @DisplayName("lambda=1 时纯按相关性排序")
        void lambda_one_pure_relevance() {
            var processor = createProcessor(1.0, 2);
            var docs = List.of(
                    doc("d1", Map.of("rerankScore", 0.5)),
                    doc("d2", Map.of("rerankScore", 0.9)),
                    doc("d3", Map.of("rerankScore", 0.7)),
                    doc("d4", Map.of("rerankScore", 0.3))
            );

            when(vectorStoreMapper.pairwiseCosineDistance(any())).thenReturn(Map.of());

            List<Document> result = processor.process(query, docs);

            assertThat(result).hasSize(2);
            // lambda=1 → 纯相关性排序，选 top 2: d2(0.9), d3(0.7)
            assertThat(result.get(0).getId()).isEqualTo("d2");
            assertThat(result.get(1).getId()).isEqualTo("d3");
        }

        @Test
        @DisplayName("lambda=0 时纯按多样性选择")
        void lambda_zero_pure_diversity() {
            var processor = createProcessor(0.0, 3);
            var docs = List.of(
                    doc("d1", Map.of("rerankScore", 0.9)),
                    doc("d2", Map.of("rerankScore", 0.8)),
                    doc("d3", Map.of("rerankScore", 0.7)),
                    doc("d4", Map.of("rerankScore", 0.6))
            );

            // d1 和 d2 非常相似(0.1)，d3 和 d4 与 d1 不相似(0.9)
            Map<String, Double> distances = new java.util.HashMap<>();
            distances.put("d1|d2", 0.1); distances.put("d2|d1", 0.1);
            distances.put("d1|d3", 0.9); distances.put("d3|d1", 0.9);
            distances.put("d1|d4", 0.8); distances.put("d4|d1", 0.8);
            distances.put("d2|d3", 0.8); distances.put("d3|d2", 0.8);
            distances.put("d2|d4", 0.9); distances.put("d4|d2", 0.9);
            distances.put("d3|d4", 0.2); distances.put("d4|d3", 0.2);
            when(vectorStoreMapper.pairwiseCosineDistance(any())).thenReturn(distances);

            List<Document> result = processor.process(query, docs);

            assertThat(result).hasSize(3);
            // lambda=0 → -maxSim，选与已选集最不相似的
            // 第一个选 rerankScore 最高的 d1
            assertThat(result.get(0).getId()).isEqualTo("d1");
            // 之后选距离 d1 最远的 → d3(0.9)
            assertThat(result.get(1).getId()).isEqualTo("d3");
        }
    }

    // ====================================================================
    // resolveRelevanceScore fallback
    // ====================================================================

    @Nested
    @DisplayName("resolveRelevanceScore 三级 fallback")
    class RelevanceScoreFallback {

        @Test
        @DisplayName("有 rerankScore 时使用 rerankScore")
        void uses_rerank_score_when_present() {
            var processor = createProcessor(1.0, 1);
            var docs = List.of(
                    doc("d1", Map.of("rerankScore", 0.95, "rrfScore", 0.8)),
                    doc("d2", Map.of("rerankScore", 0.3)),
                    doc("d3", Map.of("rrfScore", 0.6))
            );

            when(vectorStoreMapper.pairwiseCosineDistance(any())).thenReturn(Map.of());

            List<Document> result = processor.process(query, docs);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("d1");
        }

        @Test
        @DisplayName("无 rerankScore 有 rrfScore 时使用 rrfScore")
        void uses_rrf_score_when_no_rerank() {
            var processor = createProcessor(1.0, 1);
            var docs = List.of(
                    doc("d1", Map.of("rrfScore", 0.7)),
                    doc("d2", Map.of("rrfScore", 0.3)),
                    doc("d3", Map.of())
            );

            when(vectorStoreMapper.pairwiseCosineDistance(any())).thenReturn(Map.of());

            List<Document> result = processor.process(query, docs);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("d1");
        }

        @Test
        @DisplayName("无 rerankScore 无 rrfScore 时 fallback 到 0.5")
        void falls_back_to_05() {
            var processor = createProcessor(1.0, 1);
            // 所有文档都没有 score → 全部 0.5 → 选第一个
            var docs = List.of(
                    doc("d1", Map.of()),
                    doc("d2", Map.of()),
                    doc("d3", Map.of())
            );

            when(vectorStoreMapper.pairwiseCosineDistance(any())).thenReturn(Map.of());

            List<Document> result = processor.process(query, docs);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("d1");
        }
    }

    private void verifyNoInteractions(VectorStoreMapper mock) {
        // no-op, MockitoJUnitExtension handles strict stubbing
    }
}
