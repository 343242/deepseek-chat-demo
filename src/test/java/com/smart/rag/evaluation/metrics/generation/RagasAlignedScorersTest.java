package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.judge.LlmJudge.GeneratedQuestion;
import com.smart.rag.evaluation.judge.LlmJudge.JudgeVerdict;
import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ragas 对齐 LLM Scorer 测试：LlmJudge 按罐头 JSON 桩化（成功 + 解析失败路径）。
 * NoiseSensitivity 片段级 NLI 为并发调用，桩按 prompt 内容区分而非顺序。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ragas 对齐 LLM 指标")
class RagasAlignedScorersTest {

    @Mock
    private LlmJudge judge;

    @Mock
    private EmbeddingModel embeddingModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvaluationProperties props = new EvaluationProperties();
    private final DefaultScopedTasks scopedTasks = new DefaultScopedTasks();

    private static final String ANSWER = "多屏协同需要在手机和电脑登录同一华为账号并开启蓝牙。";
    private static final String GROUND_TRUTH = "多屏协同要求手机和电脑登录同一华为账号，同时开启 WLAN 和蓝牙。";
    private static final String DOC1 = "多屏协同依赖华为账号体系。";
    private static final String DOC2 = "畅连通话的资费说明。";
    private static final List<Document> DOCS = List.of(
            new Document(DOC1),
            new Document(DOC2));

    @BeforeEach
    void stubEmbedding() {
        lenient().when(embeddingModel.embed(anyString()))
                .thenReturn(new float[]{1.0f, 0.5f});
        lenient().when(embeddingModel.embed(anyList())).thenReturn(List.of(
                new float[]{1.0f, 0.5f},
                new float[]{0.9f, 0.4f},
                new float[]{1.0f, 0.5f}));
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
            var scorer = new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel, props);
            // 两次 statement 提取 + 一次分类（按序返回）
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
        @DisplayName("beta 可配：β=2 时按 F2 加权")
        void betaIsConfigurable() {
            props.getMetrics().getAnswerCorrectness().setBeta(2.0);
            var scorer = new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel, props);
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("[\"a\"]"),
                    JudgeVerdict.ok("[\"a\"]"),
                    JudgeVerdict.ok("{\"TP\": [\"a\"], \"FP\": [], \"FN\": []}"));

            double score = scorer.score("q", ANSWER, GROUND_TRUTH);

            // tp=1, fp=fn=0 → Fβ 恒 1；cosine=1 → 加权后仍 1
            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("双方均无陈述：事实性记 1.0（NaN 守卫），仅保留语义分量")
        void bothEmptyStatementsGuardsNaN() {
            var scorer = new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel, props);
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("[]"),
                    JudgeVerdict.ok("[]"));

            double score = scorer.score("q", ANSWER, GROUND_TRUTH);

            // 事实性 1.0 × 0.75 + cosine(相同桩向量)=1.0 × 0.25
            assertThat(score).isEqualTo(1.0);
            assertThat(Double.isNaN(score)).isFalse();
        }

        @Test
        @DisplayName("Judge 失败返回 -1 哨兵；无标准答案返回 -1")
        void sentinelPaths() {
            var scorer = new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel, props);
            judgeFails();
            assertThat(scorer.score("q", ANSWER, GROUND_TRUTH)).isEqualTo(-1.0);
            assertThat(scorer.score("q", ANSWER, " ")).isEqualTo(-1.0);
        }
    }

    @Nested
    @DisplayName("ContextPrecisionLlmScorer / ContextUtilizationScorer")
    class ContextPrecision {

        private ContextPrecisionLlmScorer precisionScorer() {
            return new ContextPrecisionLlmScorer(new ChunkVerdictSupport(judge, objectMapper));
        }

        private ContextUtilizationScorer utilizationScorer() {
            return new ContextUtilizationScorer(new ChunkVerdictSupport(judge, objectMapper));
        }

        @Test
        @DisplayName("平均精度：两片段都相关 = 1.0；仅第二个相关 = 0.5")
        void computesAveragePrecision() {
            var scorer = precisionScorer();

            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 1}, {\"index\": 2, \"verdict\": 1}]}");
            assertThat(scorer.score("q", GROUND_TRUTH, DOCS)).isEqualTo(1.0);

            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 0}, {\"index\": 2, \"verdict\": 1}]}");
            assertThat(scorer.score("q", GROUND_TRUTH, DOCS)).isEqualTo(0.5);

            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 0}, {\"index\": 2, \"verdict\": 0}]}");
            assertThat(scorer.score("q", GROUND_TRUTH, DOCS)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("ContextUtilization 同公式（reference-free，判决对象为生成回答）")
        void utilizationSharesFormula() {
            var scorer = utilizationScorer();

            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 1}, {\"index\": 2, \"verdict\": 0}]}");
            assertThat(scorer.score("q", ANSWER, DOCS)).isEqualTo(1.0);

            judgeReturns("{\"verdicts\": [{\"index\": 1, \"verdict\": 1}]}");
            assertThat(scorer.score("q", ANSWER, DOCS)).isEqualTo(-1.0);

            assertThat(scorer.score("q", ANSWER, List.of())).isEqualTo(0.0);
        }

        @Test
        @DisplayName("判决数量不匹配或 Judge 失败返回 -1；无片段返回 0")
        void sentinelPaths() {
            var scorer = precisionScorer();
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
        @DisplayName("双向验证 f1 模式：tp/fp（response→reference）+ fn（reference→response）")
        void computesF1TwoWay() {
            var scorer = new FactualCorrectnessScorer(judge, objectMapper, props);
            // decompose(answer) → verify(resp, ref) → decompose(ref) → verify(ref, answer)
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("[\"登录同一华为账号\", \"开启 WLAN 和蓝牙\"]"),
                    JudgeVerdict.ok("{\"verifications\": [{\"index\": 1, \"supported\": true}, "
                            + "{\"index\": 2, \"supported\": false}]}"),
                    JudgeVerdict.ok("[\"登录同一华为账号\"]"),
                    JudgeVerdict.ok("{\"verifications\": [{\"index\": 1, \"supported\": false}]}"));

            // tp=1, fp=1（response 第 2 条无支撑），fn=1（reference 唯一条未被 response 支撑）
            // P=R=0.5 → F1=0.5（round2 后仍 0.5）
            assertThat(scorer.score(GROUND_TRUTH, ANSWER)).isEqualTo(0.5);
        }

        @Test
        @DisplayName("recall 模式只算 tp/(tp+fn)；结果保留 2 位小数")
        void recallModeRoundsToTwoDecimals() {
            props.getMetrics().getFactualCorrectness().setMode("recall");
            var scorer = new FactualCorrectnessScorer(judge, objectMapper, props);
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("[\"a\", \"b\", \"c\"]"),
                    JudgeVerdict.ok("{\"verifications\": [{\"index\": 1, \"supported\": true}, "
                            + "{\"index\": 2, \"supported\": true}, {\"index\": 3, \"supported\": false}]}"),
                    JudgeVerdict.ok("[\"a\"]"),
                    JudgeVerdict.ok("{\"verifications\": [{\"index\": 1, \"supported\": true}]}"));

            // tp=2, fn=0 → 2/(2+0) = 1.0
            assertThat(scorer.score(GROUND_TRUTH, ANSWER)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("precision 模式跳过 reference→response 方向（仅 2 次 judge 调用）")
        void precisionModeSkipsReverseDirection() {
            props.getMetrics().getFactualCorrectness().setMode("precision");
            var scorer = new FactualCorrectnessScorer(judge, objectMapper, props);
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("[\"a\", \"b\"]"),
                    JudgeVerdict.ok("{\"verifications\": [{\"index\": 1, \"supported\": true}, "
                            + "{\"index\": 2, \"supported\": false}]}"));

            // tp=1, fp=1 → 1/(1+1) = 0.5
            assertThat(scorer.score(GROUND_TRUTH, ANSWER)).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Judge 失败返回 -1；无 GT 返回 -1")
        void returnsSentinel() {
            var scorer = new FactualCorrectnessScorer(judge, objectMapper, props);
            judgeFails();
            assertThat(scorer.score(GROUND_TRUTH, ANSWER)).isEqualTo(-1.0);
            assertThat(scorer.score(" ", ANSWER)).isEqualTo(-1.0);
        }
    }

    @Nested
    @DisplayName("NoiseSensitivityScorer（语句级矩阵）")
    class NoiseSensitivity {

        private NoiseSensitivityScorer scorer() {
            return new NoiseSensitivityScorer(
                    new ClaimVerificationSupport(judge, objectMapper), props, scopedTasks);
        }

        /**
         * 按内容区分桩：CLAIM_EXTRACTION（含"事实性声明"，GT/答案二选一）；
         * CLAIM_VERIFICATION（含"推导出来"）——premise 为 GROUND_TRUTH 的是 gt2answer，
         * 否则按片段（DOC1/DOC2）与主张侧（gt 侧声明含 WLAN，ans 侧不含）定位矩阵单元。
         */
        private void stubMatrix(String gtClaimsJson, String ansClaimsJson,
                                String gtCtx1, String gtCtx2,
                                String ansCtx1, String ansCtx2,
                                String ansVsRefVerdicts) {
            when(judge.evaluate(anyString())).thenAnswer(inv -> {
                String prompt = inv.getArgument(0);
                if (prompt.contains("事实性声明")) {
                    return JudgeVerdict.ok(prompt.contains(GROUND_TRUTH)
                            ? gtClaimsJson : ansClaimsJson);
                }
                if (prompt.contains(GROUND_TRUTH)) {
                    return JudgeVerdict.ok(ansVsRefVerdicts);
                }
                boolean ctx1 = prompt.contains(DOC1);
                boolean gtSide = prompt.contains("WLAN");
                return JudgeVerdict.ok(ctx1
                        ? (gtSide ? gtCtx1 : ansCtx1)
                        : (gtSide ? gtCtx2 : ansCtx2));
            });
        }

        @Test
        @DisplayName("relevant 模式：错误主张被相关片段支撑 → 命中占比")
        void relevantModeHits() {
            // gt 主张 2 条；ans 主张 2 条（第 2 条未被 reference 支撑 = incorrect）
            // ctx1 支撑全部 gt 主张（relevant）且支撑两条 ans 主张
            // → 第 2 条主张 relevantFaithful ∧ incorrect → 1/2
            stubMatrix(
                    "[\"华为账号\", \"WLAN\"]",
                    "[\"华为账号\", \"蓝牙\"]",
                    verifications(true, true), verifications(false, false),
                    verifications(true, true), verifications(false, false),
                    verifications(true, false));

            assertThat(scorer().score(ANSWER, GROUND_TRUTH, DOCS)).isEqualTo(0.5);
        }

        @Test
        @DisplayName("irrelevant 模式：错误主张仅被无关片段支撑 → 命中；同时被相关片段支撑则排他")
        void irrelevantModeExclusivity() {
            props.getMetrics().getNoiseSensitivity().setMode("irrelevant");
            // ctx1 relevant 支撑主张1；ctx2 无关且仅支撑主张2（incorrect）→ 1/2
            stubMatrix(
                    "[\"华为账号\", \"WLAN\"]",
                    "[\"华为账号\", \"蓝牙\"]",
                    verifications(true, true), verifications(false, false),
                    verifications(true, false), verifications(false, true),
                    verifications(true, false));

            assertThat(scorer().score(ANSWER, GROUND_TRUTH, DOCS)).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Judge 失败 -1；无片段 0；分解为空 -1")
        void sentinelPaths() {
            judgeFails();
            assertThat(scorer().score(ANSWER, GROUND_TRUTH, DOCS)).isEqualTo(-1.0);
            assertThat(scorer().score(ANSWER, GROUND_TRUTH, List.of())).isEqualTo(0.0);

            judgeReturns("[]");
            assertThat(scorer().score(ANSWER, GROUND_TRUTH, DOCS)).isEqualTo(-1.0);
        }

        private static String verifications(boolean... flags) {
            var sb = new StringBuilder("{\"verifications\": [");
            for (int i = 0; i < flags.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("{\"index\": ").append(i + 1)
                        .append(", \"supported\": ").append(flags[i]).append("}");
            }
            return sb.append("]}").toString();
        }
    }

    @Nested
    @DisplayName("AnswerRelevanceScorer（noncommittal）")
    class AnswerRelevance {

        private AnswerRelevanceScorer scorer() {
            return new AnswerRelevanceScorer(judge, embeddingModel, props);
        }

        @Test
        @DisplayName("均值 cosine；并非全部 noncommittal 不影响分数")
        void meanCosine() {
            when(judge.generateQuestionsWithFlags(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of(
                            new GeneratedQuestion("怎么开多屏协同？", false),
                            new GeneratedQuestion("多屏协同怎么连？", false),
                            new GeneratedQuestion("蓝牙怎么开？", true)));

            double score = scorer().score("怎么开多屏协同？", ANSWER);

            // 桩 embedding：question=[1,0.5]，生成问题两两 [1,0.5]/[0.9,0.4] 循环 → 均值 ∈ (0,1]
            assertThat(score).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
        }

        @Test
        @DisplayName("全部 noncommittal → 归 0")
        void allNoncommittalZero() {
            when(judge.generateQuestionsWithFlags(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of(
                            new GeneratedQuestion("q1", true),
                            new GeneratedQuestion("q2", true),
                            new GeneratedQuestion("q3", true)));

            assertThat(scorer().score("怎么开多屏协同？", ANSWER)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("采样全空 → -1")
        void emptySamplesSentinel() {
            when(judge.generateQuestionsWithFlags(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of());
            assertThat(scorer().score("q", ANSWER)).isEqualTo(-1.0);
        }
    }

    @Nested
    @DisplayName("ContextEntityRecallScorer")
    class ContextEntityRecall {

        @Test
        @DisplayName("参考实体 ∩ 上下文实体 / 参考实体（精确匹配）")
        void computesRecall() {
            var scorer = new ContextEntityRecallScorer(judge, objectMapper);
            when(judge.evaluate(anyString())).thenReturn(
                    JudgeVerdict.ok("{\"entities\": [\"华为账号\", \"WLAN\"]}"),
                    JudgeVerdict.ok("{\"entities\": [\"华为账号\", \"畅连通话\"]}"));

            // 交集 {华为账号} → 1/(2+1e-8) ≈ 0.5
            assertThat(scorer.score(GROUND_TRUTH, DOC1 + "\n" + DOC2))
                    .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("Judge 失败 -1")
        void sentinel() {
            var scorer = new ContextEntityRecallScorer(judge, objectMapper);
            judgeFails();
            assertThat(scorer.score(GROUND_TRUTH, DOC1)).isEqualTo(-1.0);
        }
    }

    @Nested
    @DisplayName("AnswerSimilarityScorer")
    class AnswerSimilarity {

        @Test
        @DisplayName("同向量 cosine=1；embedding 失败 -1；空串以空格兜底")
        void computesCosine() {
            var scorer = new AnswerSimilarityScorer(embeddingModel);
            assertThat(scorer.score(ANSWER, GROUND_TRUTH))
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
            assertThat(scorer.score("", ""))
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));

            when(embeddingModel.embed(anyString()))
                    .thenThrow(new IllegalStateException("embedding down"));
            assertThat(scorer.score(ANSWER, GROUND_TRUTH)).isEqualTo(-1.0);
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
            lenient().when(contextRelevance.score(anyString(), anyList()))
                    .thenReturn(0.6);

            var chunkVerdicts = new ChunkVerdictSupport(judge, objectMapper);
            var calculator = new GenerationMetricsCalculator(
                    faithfulness, contextRecall, answerRelevance, contextRelevance,
                    new AnswerCorrectnessScorer(judge, objectMapper, embeddingModel, props),
                    new NoiseSensitivityScorer(
                            new ClaimVerificationSupport(judge, objectMapper), props, scopedTasks),
                    new ContextPrecisionLlmScorer(chunkVerdicts),
                    new FactualCorrectnessScorer(judge, objectMapper, props),
                    new AnswerSimilarityScorer(embeddingModel),
                    new ContextEntityRecallScorer(judge, objectMapper),
                    new ContextUtilizationScorer(chunkVerdicts));
            judgeFails(); // LLM 新指标全部 -1 哨兵

            var metrics = calculator.calculate("q", ANSWER, GROUND_TRUTH, DOCS);

            assertThat(metrics.faithfulness()).isEqualTo(0.9);
            assertThat(metrics.contextRecall()).isEqualTo(0.8);
            assertThat(metrics.answerCorrectness()).isEqualTo(-1.0);
            assertThat(metrics.noiseSensitivity()).isEqualTo(-1.0);
            assertThat(metrics.contextUtilization()).isEqualTo(-1.0);
            assertThat(metrics.answerSimilarity())
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
            assertThat(metrics.rougeL()).isBetween(0.0, 1.0);
            assertThat(metrics.bleu()).isBetween(0.0, 1.0);
        }
    }
}
