package com.smart.rag.evaluation.metrics.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RetrievalMetricsCalculator 单元测试
 */
class RetrievalMetricsCalculatorTest {

    private final RetrievalMetricsCalculator calculator = new RetrievalMetricsCalculator();

    @Nested
    @DisplayName("Recall@K")
    class RecallAtK {

        @Test
        @DisplayName("全部命中")
        void allRelevant() {
            var result = calculator.calculate(
                    List.of("a", "b", "c"),
                    Set.of("a", "b", "c"), 3);
            assertThat(result.recall()).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("部分命中")
        void partialHit() {
            var result = calculator.calculate(
                    List.of("a", "b", "d"),
                    Set.of("a", "b", "c"), 3);
            assertThat(result.recall()).isCloseTo(2.0 / 3, within(0.001));
        }

        @Test
        @DisplayName("完全未命中")
        void noHit() {
            var result = calculator.calculate(
                    List.of("x", "y", "z"),
                    Set.of("a", "b", "c"), 3);
            assertThat(result.recall()).isCloseTo(0.0, within(0.001));
        }
    }

    @Nested
    @DisplayName("Precision@K")
    class PrecisionAtK {

        @Test
        @DisplayName("全部相关")
        void allRelevant() {
            var result = calculator.calculate(
                    List.of("a", "b", "c"),
                    Set.of("a", "b", "c"), 3);
            assertThat(result.precision()).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("部分相关")
        void partialRelevant() {
            var result = calculator.calculate(
                    List.of("a", "x", "y"),
                    Set.of("a", "b"), 3);
            assertThat(result.precision()).isCloseTo(1.0 / 3, within(0.001));
        }
    }

    @Nested
    @DisplayName("MRR")
    class MRR {

        @Test
        @DisplayName("第一个就命中")
        void firstRelevant() {
            var result = calculator.calculate(
                    List.of("a", "b", "c"),
                    Set.of("a"), 3);
            assertThat(result.mrr()).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("第三个命中")
        void thirdRelevant() {
            var result = calculator.calculate(
                    List.of("x", "y", "a"),
                    Set.of("a"), 3);
            assertThat(result.mrr()).isCloseTo(1.0 / 3, within(0.001));
        }

        @Test
        @DisplayName("未命中")
        void noRelevant() {
            var result = calculator.calculate(
                    List.of("x", "y", "z"),
                    Set.of("a"), 3);
            assertThat(result.mrr()).isCloseTo(0.0, within(0.001));
        }
    }

    @Nested
    @DisplayName("NDCG")
    class NDCG {

        @Test
        @DisplayName("完美排序")
        void perfectRanking() {
            var result = calculator.calculate(
                    List.of("a", "b", "c"),
                    Set.of("a", "b", "c"), 3);
            assertThat(result.ndcg()).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("无相关文档")
        void noRelevantDocs() {
            var result = calculator.calculate(
                    List.of("x", "y", "z"),
                    Set.of("a", "b"), 3);
            assertThat(result.ndcg()).isCloseTo(0.0, within(0.001));
        }
    }

    @Nested
    @DisplayName("Context Precision")
    class ContextPrecision {

        @Test
        @DisplayName("相关文档排前面")
        void relevantFirst() {
            var result = calculator.calculate(
                    List.of("a", "b", "x", "y"),
                    Set.of("a", "b"), 4);
            // a@1=1/1, b@2=2/2 → (1+1)/(1+1) = 1.0
            assertThat(result.contextPrecision()).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("相关文档散布")
        void scattered() {
            var result = calculator.calculate(
                    List.of("x", "a", "y", "b"),
                    Set.of("a", "b"), 4);
            // a@2=1/2, b@4=2/4 → (0.5+0.5)/2 = 0.5
            assertThat(result.contextPrecision()).isCloseTo(0.5, within(0.001));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("空检索结果")
        void emptyRetrieved() {
            var result = calculator.calculate(
                    List.of(), Set.of("a"), 5);
            assertThat(result.recall()).isCloseTo(0.0, within(0.001));
            assertThat(result.precision()).isCloseTo(0.0, within(0.001));
        }

        @Test
        @DisplayName("空 ground truth")
        void emptyGroundTruth() {
            var result = calculator.calculate(
                    List.of("a", "b"), Set.of(), 5);
            assertThat(result.recall()).isCloseTo(0.0, within(0.001));
        }

        @Test
        @DisplayName("null 安全")
        void nullSafety() {
            var result = calculator.calculate(null, null, 5);
            assertThat(result.recall()).isCloseTo(0.0, within(0.001));
        }
    }
}
