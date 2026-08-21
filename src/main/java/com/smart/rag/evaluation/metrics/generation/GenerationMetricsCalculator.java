package com.smart.rag.evaluation.metrics.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生成指标聚合计算器
 * <p>
 * 聚合生成侧指标：原有四个（Faithfulness / Context Recall / Answer Relevance / Context Relevance）
 * + ragas 0.4.3 对齐的 LLM 指标（AnswerCorrectness / NoiseSensitivity / ContextPrecisionLlm /
 * FactualCorrectness / ContextEntityRecall / ContextUtilization）+ AnswerSimilarity（纯 embedding）
 * + 两个确定性字符串指标（RougeL / Bleu）。
 * Judge 失败的指标为 -1 哨兵，聚合 SQL 沿用过滤断言。
 * </p>
 */
@Component
public class GenerationMetricsCalculator {

    private static final Logger log = LoggerFactory.getLogger(GenerationMetricsCalculator.class);

    private final FaithfulnessScorer faithfulnessScorer;
    private final ContextRecallScorer contextRecallScorer;
    private final AnswerRelevanceScorer answerRelevanceScorer;
    private final ContextRelevanceScorer contextRelevanceScorer;
    private final AnswerCorrectnessScorer answerCorrectnessScorer;
    private final NoiseSensitivityScorer noiseSensitivityScorer;
    private final ContextPrecisionLlmScorer contextPrecisionLlmScorer;
    private final FactualCorrectnessScorer factualCorrectnessScorer;
    private final AnswerSimilarityScorer answerSimilarityScorer;
    private final ContextEntityRecallScorer contextEntityRecallScorer;
    private final ContextUtilizationScorer contextUtilizationScorer;

    public GenerationMetricsCalculator(FaithfulnessScorer faithfulnessScorer,
                                       ContextRecallScorer contextRecallScorer,
                                       AnswerRelevanceScorer answerRelevanceScorer,
                                       ContextRelevanceScorer contextRelevanceScorer,
                                       AnswerCorrectnessScorer answerCorrectnessScorer,
                                       NoiseSensitivityScorer noiseSensitivityScorer,
                                       ContextPrecisionLlmScorer contextPrecisionLlmScorer,
                                       FactualCorrectnessScorer factualCorrectnessScorer,
                                       AnswerSimilarityScorer answerSimilarityScorer,
                                       ContextEntityRecallScorer contextEntityRecallScorer,
                                       ContextUtilizationScorer contextUtilizationScorer) {
        this.faithfulnessScorer = faithfulnessScorer;
        this.contextRecallScorer = contextRecallScorer;
        this.answerRelevanceScorer = answerRelevanceScorer;
        this.contextRelevanceScorer = contextRelevanceScorer;
        this.answerCorrectnessScorer = answerCorrectnessScorer;
        this.noiseSensitivityScorer = noiseSensitivityScorer;
        this.contextPrecisionLlmScorer = contextPrecisionLlmScorer;
        this.factualCorrectnessScorer = factualCorrectnessScorer;
        this.answerSimilarityScorer = answerSimilarityScorer;
        this.contextEntityRecallScorer = contextEntityRecallScorer;
        this.contextUtilizationScorer = contextUtilizationScorer;
    }

    /**
     * 计算所有生成侧指标
     *
     * @param question          用户问题
     * @param answer            LLM 生成的回答
     * @param groundTruthAnswer 标准答案
     * @param contextDocs       检索到的文档片段
     * @return 生成指标（Judge 失败的指标为 -1）
     */
    public GenerationMetrics calculate(String question, String answer,
                                       String groundTruthAnswer,
                                       List<Document> contextDocs) {
        String contextText = ContextTextBuilder.build(contextDocs);
        boolean hasGroundTruth = groundTruthAnswer != null && !groundTruthAnswer.isBlank();
        // null 与空列表统一为空列表；空上下文时相关 LLM 指标由各自 Scorer 返回 0（无片段即无噪声/无排序）
        var docs = contextDocs == null ? List.<Document>of() : contextDocs;

        double faithfulness = faithfulnessScorer.score(answer, contextText);
        double contextRecall = hasGroundTruth
                ? contextRecallScorer.score(groundTruthAnswer, contextText) : -1;
        double answerRelevance = answerRelevanceScorer.score(question, answer);
        double contextRelevance = contextRelevanceScorer.score(question, docs);

        double answerCorrectness = hasGroundTruth
                ? answerCorrectnessScorer.score(question, answer, groundTruthAnswer) : -1;
        double noiseSensitivity = hasGroundTruth
                ? noiseSensitivityScorer.score(answer, groundTruthAnswer, docs) : -1;
        double contextPrecisionLlm = hasGroundTruth
                ? contextPrecisionLlmScorer.score(question, groundTruthAnswer, docs) : -1;
        double factualCorrectness = hasGroundTruth
                ? factualCorrectnessScorer.score(groundTruthAnswer, answer) : -1;
        double rougeL = hasGroundTruth ? RougeLScorer.score(answer, groundTruthAnswer) : -1;
        double bleu = hasGroundTruth ? BleuScorer.score(answer, groundTruthAnswer) : -1;
        double answerSimilarity = hasGroundTruth
                ? answerSimilarityScorer.score(answer, groundTruthAnswer) : -1;
        double contextEntityRecall = hasGroundTruth
                ? contextEntityRecallScorer.score(groundTruthAnswer, contextText) : -1;
        double contextUtilization = contextUtilizationScorer.score(question, answer, docs);

        log.debug("Generation metrics: faithfulness={}, contextRecall={}, answerRelevance={}, "
                        + "contextRelevance={}, answerCorrectness={}, noiseSensitivity={}, "
                        + "contextPrecisionLlm={}, factualCorrectness={}, rougeL={}, bleu={}, "
                        + "answerSimilarity={}, contextEntityRecall={}, contextUtilization={}",
                faithfulness, contextRecall, answerRelevance, contextRelevance,
                answerCorrectness, noiseSensitivity, contextPrecisionLlm, factualCorrectness,
                rougeL, bleu, answerSimilarity, contextEntityRecall, contextUtilization);

        return new GenerationMetrics(faithfulness, contextRecall, answerRelevance,
                contextRelevance, answerCorrectness, noiseSensitivity, contextPrecisionLlm,
                factualCorrectness, rougeL, bleu, answerSimilarity, contextEntityRecall,
                contextUtilization);
    }
}
