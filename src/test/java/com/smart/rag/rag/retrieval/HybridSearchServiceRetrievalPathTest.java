package com.smart.rag.rag.retrieval;

import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.rag.config.RagRetrievalProperties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * OCP regression tests for RetrievalPath-driven HybridSearchService.
 * <p>
 * Validates AC4 (fork count = paths.size()), AC5 (SCORE_WEIGHTED vs RANK_ONLY formula),
 * AC6 (degradation), and AC7 (ScoredDocument is top-level public record).
 */
class HybridSearchServiceRetrievalPathTest {

    private static RagRetrievalProperties defaultProperties() {
        return new RagRetrievalProperties(
                false,  // queryRewriteEnabled
                "jiebacfg",
                10,     // vectorTopK
                10,     // bm25TopK
                60,     // rrfK
                60,     // fusionTopK
                20,     // rerankTopN
                false,  // mmrEnabled
                0.7,    // mmrLambda
                5,      // mmrTopK
                0.0,    // similarityThreshold
                null, null    // queryRewriteModel, queryRewriteTemperature
        );
    }

    private static Document doc(String id, String content) {
        return new Document(id, content, new java.util.HashMap<>());
    }

    /**
     * Test-only stub retrieval path that returns fixed documents.
     */
    private static class StubRetrievalPath implements RetrievalPath {

        private final String pathName;
        private final RetrievalPath.RrfWeighting weighting;
        private final List<ScoredDocument> results;

        StubRetrievalPath(String pathName, RetrievalPath.RrfWeighting weighting, List<ScoredDocument> results) {
            this.pathName = pathName;
            this.weighting = weighting;
            this.results = results;
        }

        @Override
        public String name() {
            return pathName;
        }

        @Override
        public List<ScoredDocument> search(String query, long userId, @Nullable Long teamId) {
            return results;
        }

        @Override
        public RetrievalPath.RrfWeighting rrfWeighting() {
            return weighting;
        }
    }

    /**
     * Test-only stub retrieval path that always throws.
     */
    private static class FailingRetrievalPath implements RetrievalPath {

        private final String pathName;
        private final RetrievalPath.RrfWeighting weighting;

        FailingRetrievalPath(String pathName, RetrievalPath.RrfWeighting weighting) {
            this.pathName = pathName;
            this.weighting = weighting;
        }

        @Override
        public String name() {
            return pathName;
        }

        @Override
        public List<ScoredDocument> search(String query, long userId, @Nullable Long teamId) {
            throw new RuntimeException("forced failure");
        }

        @Override
        public RetrievalPath.RrfWeighting rrfWeighting() {
            return weighting;
        }
    }

    // ====================================================================
    // AC4: Fork count driven by paths.size() (OCP)
    // ====================================================================

    @Nested
    @DisplayName("AC4: Fork count = paths.size()")
    class ForkCountTests {

        @Test
        @DisplayName("3 paths → all 3 contribute results (OCP)")
        void three_paths_all_used() {
            var props = defaultProperties();
            var stubA = new StubRetrievalPath("stub-a", RetrievalPath.RrfWeighting.SCORE_WEIGHTED,
                    List.of(new ScoredDocument(doc("d1", "c1"), 1, 0.8, "test-path")));
            var stubB = new StubRetrievalPath("stub-b", RetrievalPath.RrfWeighting.RANK_ONLY,
                    List.of(new ScoredDocument(doc("d2", "c2"), 1, 0.0, "test-path")));
            var stubC = new StubRetrievalPath("stub-c", RetrievalPath.RrfWeighting.SCORE_WEIGHTED,
                    List.of());

            ScopedTasks scopedTasks = new DefaultScopedTasks();
            var service = new HybridSearchService(List.of(stubA, stubB, stubC), props, new QueryNormalizer(), scopedTasks, null);

            List<Document> result = service.hybridSearch("test query", 1L, null);

            // d1 and d2 both returned (from two different paths)
            assertThat(result).hasSizeGreaterThanOrEqualTo(2);
            assertThat(result.stream().map(Document::getId).toList()).contains("d1", "d2");
        }

        @Test
        @DisplayName("2 paths → both contribute")
        void two_paths_both_used() {
            var props = defaultProperties();
            var vectorPath = new StubRetrievalPath("vector-search",
                    RetrievalPath.RrfWeighting.SCORE_WEIGHTED,
                    List.of(new ScoredDocument(doc("d1", "c1"), 1, 0.9, "test-path")));
            var bm25Path = new StubRetrievalPath("bm25-search",
                    RetrievalPath.RrfWeighting.RANK_ONLY,
                    List.of(new ScoredDocument(doc("d2", "c2"), 1, 0.0, "test-path")));

            var service = new HybridSearchService(List.of(vectorPath, bm25Path), props, new QueryNormalizer(), new DefaultScopedTasks(), null);

            List<Document> result = service.hybridSearch("test", 1L, null);

            assertThat(result).hasSize(2);
            assertThat(result.stream().map(Document::getId).toList()).contains("d1", "d2");
        }

        @Test
        @DisplayName("1 path → single-path RRF produces correct output")
        void single_path_correct_output() {
            var props = defaultProperties();
            var vectorPath = new StubRetrievalPath("vector-search",
                    RetrievalPath.RrfWeighting.SCORE_WEIGHTED,
                    List.of(
                            new ScoredDocument(doc("d1", "content1"), 1, 0.9, "test-path"),
                            new ScoredDocument(doc("d2", "content2"), 2, 0.5, "test-path")
                    ));

            var service = new HybridSearchService(List.of(vectorPath), props, new QueryNormalizer(), new DefaultScopedTasks(), null);

            List<Document> result = service.hybridSearch("test", 1L, null);

            assertThat(result).hasSize(2);
            // d1 should rank higher (higher score → higher RRF contribution)
            assertThat(result.get(0).getId()).isEqualTo("d1");
            assertThat(result.get(1).getId()).isEqualTo("d2");
        }
    }

    // ====================================================================
    // AC5: rrfFusion SCORE_WEIGHTED vs RANK_ONLY formula selection
    // ====================================================================

    @Nested
    @DisplayName("AC5: RRF formula selection by weighting")
    class RrfFormulaTests {

        @Test
        @DisplayName("SCORE_WEIGHTED path: contribution = score * 1/(k+rank)")
        void score_weighted_formula() {
            var props = defaultProperties(); // rrfK = 60
            var d1 = doc("d1", "c1");
            // score=0.6, rank=1 → contribution = 0.6 * 1/(60+1) = 0.6/61
            var path = new StubRetrievalPath("weighted", RetrievalPath.RrfWeighting.SCORE_WEIGHTED,
                    List.of(new ScoredDocument(d1, 1, 0.6, "test-path")));

            var service = new HybridSearchService(List.of(path), props, new QueryNormalizer(), new DefaultScopedTasks(), null);

            List<Document> result = service.hybridSearch("test", 1L, null);

            assertThat(result).hasSize(1);
            double expectedRrf = 0.6 * (1.0 / (60 + 1));
            assertThat((Double) result.get(0).getMetadata().get("rrfScore"))
                    .isCloseTo(expectedRrf, within(1e-9));
        }

        @Test
        @DisplayName("RANK_ONLY path: contribution = 1/(k+rank)")
        void rank_only_formula() {
            var props = defaultProperties(); // rrfK = 60
            var d1 = doc("d1", "c1");
            // rank=2 → contribution = 1/(60+2) = 1/62
            var path = new StubRetrievalPath("rank-only", RetrievalPath.RrfWeighting.RANK_ONLY,
                    List.of(new ScoredDocument(d1, 2, 0.0, "test-path")));

            var service = new HybridSearchService(List.of(path), props, new QueryNormalizer(), new DefaultScopedTasks(), null);

            List<Document> result = service.hybridSearch("test", 1L, null);

            assertThat(result).hasSize(1);
            double expectedRrf = 1.0 / (60 + 2);
            assertThat((Double) result.get(0).getMetadata().get("rrfScore"))
                    .isCloseTo(expectedRrf, within(1e-9));
        }

        @Test
        @DisplayName("Mixed weighting: vector (SCORE_WEIGHTED) + bm25 (RANK_ONLY)")
        void mixed_weighting_fusion() {
            var props = defaultProperties(); // rrfK = 60
            var d1 = doc("d1", "c1");
            var d2 = doc("d2", "c2");

            // Vector: d1 at rank 1 score 0.5, d2 at rank 2 score 0.3
            var vectorPath = new StubRetrievalPath("vector-search",
                    RetrievalPath.RrfWeighting.SCORE_WEIGHTED,
                    List.of(
                            new ScoredDocument(d1, 1, 0.5, "test-path"),
                            new ScoredDocument(d2, 2, 0.3, "test-path")
                    ));
            // BM25: d2 at rank 1
            var bm25Path = new StubRetrievalPath("bm25-search",
                    RetrievalPath.RrfWeighting.RANK_ONLY,
                    List.of(new ScoredDocument(d2, 1, 0.0, "test-path")));

            var service = new HybridSearchService(List.of(vectorPath, bm25Path), props, new QueryNormalizer(), new DefaultScopedTasks(), null);

            List<Document> result = service.hybridSearch("test", 1L, null);

            assertThat(result).hasSize(2);
            // d2 gets vector contribution (0.3 * 1/62) + bm25 contribution (1/61)
            // d1 gets only vector contribution (0.5 * 1/61)
            // d2 should rank higher because bm25 boost
            assertThat(result.get(0).getId()).isEqualTo("d2");
            assertThat(result.get(1).getId()).isEqualTo("d1");
        }
    }

    // ====================================================================
    // AC6: Degradation behavior
    // ====================================================================

    @Nested
    @DisplayName("AC6: Degradation behavior")
    class DegradationTests {

        @Test
        @DisplayName("All paths fail → ServiceException with exact message")
        void all_fail_throws() {
            var props = defaultProperties();
            var failPath = new FailingRetrievalPath("fail",
                    RetrievalPath.RrfWeighting.SCORE_WEIGHTED);

            var service = new HybridSearchService(List.of(failPath), props, new QueryNormalizer(), new DefaultScopedTasks(), null);

            assertThatThrownBy(() -> service.hybridSearch("test", 1L, null))
                    .isInstanceOf(ServiceException.class)
                    .hasMessage("向量检索和 BM25 检索均不可用");
        }

        @Test
        @DisplayName("Partial failure → returns successful path results")
        void partial_fail_degrades() {
            var props = defaultProperties();
            var d1 = doc("d1", "c1");
            var okPath = new StubRetrievalPath("ok", RetrievalPath.RrfWeighting.SCORE_WEIGHTED,
                    List.of(new ScoredDocument(d1, 1, 0.8, "test-path")));
            var failPath = new FailingRetrievalPath("fail",
                    RetrievalPath.RrfWeighting.RANK_ONLY);

            var service = new HybridSearchService(List.of(okPath, failPath), props, new QueryNormalizer(), new DefaultScopedTasks(), null);

            List<Document> result = service.hybridSearch("test", 1L, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("d1");
        }
    }
}
