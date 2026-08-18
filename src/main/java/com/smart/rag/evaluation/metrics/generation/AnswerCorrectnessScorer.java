package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 答案正确性评分器（翻译 ragas AnswerCorrectness，legacy 路径）。
 * <p>
 * 两步分离：TP/FP/FN 分类（事实性）+ embedding 语义相似度，按 ragas 默认权重
 * [0.75, 0.25] 加权：score = 0.75 × F1 + 0.25 × cosine(answer, ground_truth)。
 * 哨兵约定同 {@link FaithfulnessScorer}（Judge 失败 -1；无声明答案事实性记 1.0）。
 * </p>
 */
@Component
public class AnswerCorrectnessScorer {

    private static final Logger log = LoggerFactory.getLogger(AnswerCorrectnessScorer.class);

    private static final double FACTUALITY_WEIGHT = 0.75;
    private static final double SIMILARITY_WEIGHT = 0.25;

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;

    public AnswerCorrectnessScorer(LlmJudge judge, ObjectMapper objectMapper,
                                   EmbeddingModel embeddingModel) {
        this.judge = judge;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
    }

    /**
     * @param question         用户问题
     * @param answer           生成的回答
     * @param groundTruthAnswer 标准答案
     * @return 正确性分数（0-1），Judge 失败 -1
     */
    public double score(String question, String answer, String groundTruthAnswer) {
        if (groundTruthAnswer == null || groundTruthAnswer.isBlank()) {
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
            return similarity < 0 ? -1 : FACTUALITY_WEIGHT * 1.0 + SIMILARITY_WEIGHT * similarity;
        }

        var classification = classifyStatements(question, answerStatements, gtStatements);
        if (classification.isEmpty()) {
            return -1;
        }
        int tp = classification.get().size("TP");
        int fp = classification.get().size("FP");
        int fn = classification.get().size("FN");
        double denominator = 2.0 * tp + fp + fn;
        if (denominator == 0.0) {
            // 陈述非空但分类结果全空：LLM 输出与陈述数不匹配，判无效而非放 NaN 进聚合
            return -1;
        }
        double f1 = (2.0 * tp) / denominator;
        double similarity = semanticSimilarity(answer, groundTruthAnswer);
        if (similarity < 0) {
            // embedding 失败：与模块哨兵约定一致返回 -1，避免把"语义分 0"混入加权拉低结果
            return -1;
        }
        return FACTUALITY_WEIGHT * f1 + SIMILARITY_WEIGHT * similarity;
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
