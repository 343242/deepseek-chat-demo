package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.judge.LlmJudge.JudgeVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ragas 对齐的四个 LLM Scorer 测试：LlmJudge 按罐头 JSON 桩化（成功 + 解析失败路径）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ragas 对齐 LLM 指标")
class RagasAlignedScorersTest {

    @Mock
    private LlmJudge judge;

    @Mock
    private EmbeddingModel embeddingModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ANSWER = "多屏协同需要在手机和电脑登录同一华为账号并开启蓝牙。";
    private static final String GROUND_TRUTH = "多屏协同要求手机和电脑登录同一华为账号，同时开启 WLAN 和蓝牙。";
    private static final List<Document> DOCS = List.of(
            new Document("多屏协同依赖华为账号体系。"),
            new Document("畅连通话的资费说明。"));

    @BeforeEach
    void stubEmbedding() {
        lenient().when(embeddingModel.embed(anyString()))
                .thenReturn(new float[]{1.0f, 0.5f});
    }

    private void judgeReturns(String json) {
        when(judge.evaluate(anyString())).thenReturn(JudgeVerdict.ok(json));
    }

    private void judgeFails() {
        when(judge.evaluate(anyString())).thenReturn(
                JudgeVerdict.failed("模拟 Judge 失败"));
    }

    @Nested
    @DisplayName("AnswerCorrectnessScorer")
    class AnswerCorrectness {

        @Test
        @DisplayName("TP/FP/FN F1 + 语义相似度 0.75/0.25 加权")
        void computesWeightedScore() {
            var scorer = new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel);
            // 两次 statement 提取 + 一次分类 + 一次不正确判断（按序返回）
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("[\"多屏协同需要登录同一华为账号\", \"需要开启蓝牙\"]"),
                    JudgeVerdict.ok("[\"多屏协同需要登录同一华为账号\"]"),
                    JudgeVerdict.ok("{\"TP\": [\"多屏协同需要登录同一华为账号\"], \"FP\": [\"需要开启蓝牙\"], \"FN\": []}"));

            double score = scorer.score("怎么用多屏协同？", ANSWER, GROUND_TRUTH);

            // tp=1, fp=1, fn=0 → F1 = 2/3；embedding 桩返回相同向量 → cosine=1
            assertThat(score).isCloseTo(0.75 * (2.0 / 3.0) + 0.25 * 1.0,
                    org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("Judge 失败返回 -1 哨兵")
        void returnsSentinelOnJudgeFailure() {
            var scorer = new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel);
            judgeFails();
            assertThat(scorer.score("q", ANSWER, GROUND_TRUTH)).isEqualTo(-1.0);
        }

        @Test
        @DisplayName("无标准答案返回 -1")
        void returnsSentinelWithoutGroundTruth() {
            var scorer = new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel);
            assertThat(scorer.score("q", ANSWER, " ")).isEqualTo(-1.0);
        }
    }

    @Nested
    @DisplayName("ContextPrecisionLlmScorer")
    class ContextPrecision {

        @Test
        @DisplayName("平均精度：两片段都相关 = 1.0；仅第二个相关 = 0.5")
        void computesAveragePrecision() {
            var scorer = new ContextPrecisionLlmScorer(judge, objectMapper);

            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 1}, {\"index\": 2, \"verdict\": 1}]}");
            assertThat(scorer.score("q", GROUND_TRUTH, DOCS)).isEqualTo(1.0);

            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 0}, {\"index\": 2, \"verdict\": 1}]}");
            assertThat(scorer.score("q", GROUND_TRUTH, DOCS)).isEqualTo(0.5);

            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 0}, {\"index\": 2, \"verdict\": 0}]}");
            assertThat(scorer.score("q", GROUND_TRUTH, DOCS)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("判决数量不匹配或 Judge 失败返回 -1；无片段返回 0")
        void sentinelPaths() {
            var scorer = new ContextPrecisionLlmScorer(judge, objectMapper);
            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 1}]}");
            assertThat(scorer.score("q", GROUND_TRUTH, DOCS)).isEqualTo(-1.0);

            judgeFails();
            assertThat(scorer.score("q", GROUND_TRUTH, DOCS)).isEqualTo(-1.0);

            assertThat(scorer.score("q", GROUND_TRUTH, List.of())).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("FactualCorrectnessScorer")
    class FactualCorrectness {

        @Test
        @DisplayName("主张分解 + NLI 验证 → F-beta")
        void computesFBeta() {
            var scorer = new FactualCorrectnessScorer(judge, objectMapper);
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("[\"登录同一华为账号\", \"开启 WLAN 和蓝牙\"]"),
                    JudgeVerdict.ok("{\"verifications\": [{\"index\": 1, \"supported\": true}, "
                            + "{\"index\": 2, \"supported\": false}]}"));

            // tp=1, fn=1 → (1+1)*1 / ((1+1)*1 + 1*1) = 2/3
            assertThat(scorer.score(GROUND_TRUTH, ANSWER))
                    .isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("Judge 失败返回 -1")
        void returnsSentinel() {
            var scorer = new FactualCorrectnessScorer(judge, objectMapper);
            judgeFails();
            assertThat(scorer.score(GROUND_TRUTH, ANSWER)).isEqualTo(-1.0);
        }
    }

    @Nested
    @DisplayName("NoiseSensitivityScorer")
    class NoiseSensitivity {

        @Test
        @DisplayName("无关片段支撑回答且回答不正确 → 1；其余 → 0")
        void detectsNoise() {
            var scorer = new NoiseSensitivityScorer(judge, objectMapper);
            // 片段1 相关且支撑；片段2 无关但支撑（噪声）→ irrelevantFaithful=true；回答不正确
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("{\"chunks\": [{\"index\": 1, \"useful\": true, \"supports_answer\": true}, "
                            + "{\"index\": 2, \"useful\": false, \"supports_answer\": true}]}"),
                    JudgeVerdict.ok("{\"correct\": false}"));

            assertThat(scorer.score("q", ANSWER, GROUND_TRUTH, DOCS)).isEqualTo(1.0);

            // 片段2 无关也不支撑 → 0
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("{\"chunks\": [{\"index\": 1, \"useful\": true, \"supports_answer\": true}, "
                            + "{\"index\": 2, \"useful\": false, \"supports_answer\": false}]}"),
                    JudgeVerdict.ok("{\"correct\": false}"));
            assertThat(scorer.score("q", ANSWER, GROUND_TRUTH, DOCS)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Judge 失败 -1；无片段 0")
        void sentinelPaths() {
            var scorer = new NoiseSensitivityScorer(judge, objectMapper);
            judgeFails();
            assertThat(scorer.score("q", ANSWER, GROUND_TRUTH, DOCS)).isEqualTo(-1.0);
            assertThat(scorer.score("q", ANSWER, GROUND_TRUTH, List.of())).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("GenerationMetricsCalculator 接线")
    class Calculator {

        @Test
        @DisplayName("全部指标聚合进 record，字符串指标确定性计算")
        void wiresAllMetrics() {
            var faithfulness = org.mockito.Mockito.mock(FaithfulnessScorer.class);
            var contextRecall = org.mockito.Mockito.mock(ContextRecallScorer.class);
            var answerRelevance = org.mockito.Mockito.mock(AnswerRelevanceScorer.class);
            var contextRelevance = org.mockito.Mockito.mock(ContextRelevanceScorer.class);
            lenient().when(faithfulness.score(anyString(), anyString())).thenReturn(0.9);
            lenient().when(contextRecall.score(anyString(), anyString())).thenReturn(0.8);
            lenient().when(answerRelevance.score(anyString(), anyString())).thenReturn(0.7);
            lenient().when(contextRelevance.score(anyString(), org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(0.6);

            var calculator = new GenerationMetricsCalculator(
                    faithfulness, contextRecall, answerRelevance, contextRelevance,
                    new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel),
                    new NoiseSensitivityScorer(judge, objectMapper),
                    new ContextPrecisionLlmScorer(judge, objectMapper),
                    new FactualCorrectnessScorer(judge, objectMapper));
            judgeFails(); // LLM 新指标全部 -1 哨兵

            var metrics = calculator.calculate("q", ANSWER, GROUND_TRUTH, DOCS);

            assertThat(metrics.faithfulness()).isEqualTo(0.9);
            assertThat(metrics.contextRecall()).isEqualTo(0.8);
            assertThat(metrics.answerCorrectness()).isEqualTo(-1.0);
            assertThat(metrics.noiseSensitivity()).isEqualTo(-1.0);
            assertThat(metrics.rougeL()).isBetween(0.0, 1.0);
            assertThat(metrics.bleu()).isBetween(0.0, 1.0);
        }
    }
}
