package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 答案正确性评分器（翻译 ragas AnswerCorrectness）。
 * <p>
 * 两步分离：TP/FP/FN 分类（事实性 F-beta）+ embedding 语义相似度，按可配权重加权
 * （ragas 默认 [0.75, 0.25]，beta 默认 1.0）：
 * score = factualityWeight × Fβ + similarityWeight × cosine(answer, ground_truth)。
 * β&lt;1 偏精度（重罚幻觉）、β&gt;1 偏召回（重罚遗漏）。
 * 哨兵约定同 {@link FaithfulnessScorer}（Judge 失败 -1；无声明答案事实性记 1.0）。
 * </p>
 */
@Component
public class AnswerCorrectnessScorer {

    private static final Logger log = LoggerFactory.getLogger(AnswerCorrectnessScorer.class);

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;
    private final EvaluationProperties props;

    public AnswerCorrectnessScorer(LlmJudge judge, ObjectMapper objectMapper,
                                   EmbeddingModel embeddingModel, EvaluationProperties props) {
        this.judge = judge;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
        this.props = props;
    }

    /**
     * @param question          用户问题
     * @param answer            生成的回答
     * @param groundTruthAnswer 标准答案
     * @return 正确性分数（0-1），Judge 失败 -1
     */
    public double score(String question, String answer, String groundTruthAnswer) {
        if (groundTruthAnswer == null || groundTruthAnswer.isBlank()) {
            return -1;
        }
        double factualityWeight = props.getMetrics().getAnswerCorrectness().getFactualityWeight();
        double similarityWeight = props.getMetrics().getAnswerCorrectness().getSimilarityWeight();
        double beta = props.getMetrics().getAnswerCorrectness().getBeta();
        if (factualityWeight < 0 || similarityWeight < 0
                || factualityWeight + similarityWeight == 0) {
            log.warn("AnswerCorrectness 权重配置非法: [{}, {}]", factualityWeight, similarityWeight);
            return -1;
        }

        var answerStatementsOpt = extractStatements(answer);
        var gtStatementsOpt = extractStatements(groundTruthAnswer);
        if (answerStatementsOpt.isEmpty() || gtStatementsOpt.isEmpty()) {
            return -1; // Judge 失败
        }
        var answerStatements = answerStatementsOpt.get();
        var gtStatements = gtStatementsOpt.get();
        if (answerStatements.isEmpty() && gtStatements.isEmpty()) {
            // 双方均无陈述：事实性记 1.0（无声明即无错误），仅保留语义分量
            double similarity = semanticSimilarity(answer, groundTruthAnswer);
            return similarity < 0 ? -1 : factualityWeight * 1.0 + similarityWeight * similarity;
        }

        var classification = classifyStatements(question, answerStatements, gtStatements);
        if (classification.isEmpty()) {
            return -1;
        }
        int tp = classification.get().size("TP");
        int fp = classification.get().size("FP");
        int fn = classification.get().size("FN");
        // ragas fbeta：P=R=0（分类全空）时事实分记 0，与语义分量照常加权
        double fBeta = FactualCorrectnessScorer.fbeta(tp, fp, fn, beta);
        double similarity = semanticSimilarity(answer, groundTruthAnswer);
        if (similarity < 0) {
            // embedding 失败：与模块哨兵约定一致返回 -1，避免把"语义分 0"混入加权拉低结果
            return -1;
        }
        return factualityWeight * fBeta + similarityWeight * similarity;
    }

    private Optional<List<String>> extractStatements(String text) {
        var prompt = GenerationPrompts.STATEMENT_EXTRACTION.formatted(text);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Statement 提取失败: {}", verdict.errorMessage());
            return Optional.empty();
        }
        try {
            var json = JsonExtractorUtil.extractJson(verdict.rawJson());
            return Optional.of(new ArrayList<>(
                    objectMapper.readValue(json, new TypeReference<List<String>>() {
                    })));
        } catch (Exception e) {
            log.warn("Statement 解析失败: {}", e);
            return Optional.empty();
        }
    }

    private Optional<Classification> classifyStatements(String question,
                                                        List<String> answerStatements,
                                                        List<String> gtStatements) {
        String answerJson;
        String gtJson;
        try {
            answerJson = objectMapper.writeValueAsString(answerStatements);
            gtJson = objectMapper.writeValueAsString(gtStatements);
        } catch (Exception e) {
            return Optional.empty();
        }
        var prompt = GenerationPrompts.TP_FP_FN_CLASSIFICATION.formatted(question, answerJson, gtJson);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("TP/FP/FN 分类失败: {}", verdict.errorMessage());
            return Optional.empty();
        }
        try {
            var json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, List<String>> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, List<String>>>() {
                    });
            return Optional.of(new Classification(parsed));
        } catch (Exception e) {
            log.warn("TP/FP/FN 解析失败: {}", e);
            return Optional.empty();
        }
    }

    /**
     * @return 语义相似度（0-1）；embedding 失败返回 -1 哨兵（与模块"无效评测返回 -1"约定一致）
     */
    private double semanticSimilarity(String answer, String groundTruth) {
        try {
            var a = embeddingModel.embed(answer);
            var b = embeddingModel.embed(groundTruth);
            return VectorMathUtil.cosine(a, b);
        } catch (Exception e) {
            log.warn("语义相似度计算失败: {}", e);
            return -1;
        }
    }

    private record Classification(Map<String, List<String>> categories) {
        int size(String key) {
            return categories.getOrDefault(key, List.of()).size();
        }
    }
}
