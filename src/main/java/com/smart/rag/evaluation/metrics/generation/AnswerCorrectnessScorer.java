package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Profile;
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
@Profile("evaluation")
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
            return FACTUALITY_WEIGHT * 1.0
                    + SIMILARITY_WEIGHT * semanticSimilarity(answer, groundTruthAnswer);
        }

        var classification = classifyStatements(question, answerStatements, gtStatements);
        if (classification.isEmpty()) {
            return -1;
        }
        int tp = classification.get().size("TP");
        int fp = classification.get().size("FP");
        int fn = classification.get().size("FN");
        double f1 = (2.0 * tp) / (2.0 * tp + fp + fn);
        return FACTUALITY_WEIGHT * f1
                + SIMILARITY_WEIGHT * semanticSimilarity(answer, groundTruthAnswer);
    }

    private Optional<List<String>> extractStatements(String text) {
        var prompt = """
                给定以下文本，提取其中所有独立的事实性陈述，每条一个可验证的原子事实。

                文本：
                %s

                输出 JSON 数组（不要输出其他内容）：
                ["陈述1", "陈述2"]
                """.formatted(text);
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
            log.warn("Statement 解析失败: {}", e.getMessage());
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
        var prompt = """
                给定问题、回答的陈述列表与标准答案的陈述列表，对回答的每条陈述分类：
                - TP（真阳性）：回答中的陈述，且被标准答案的一条或多条陈述直接支撑
                - FP（假阳性）：回答中的陈述，但不被标准答案任何陈述支撑
                - FN（假阴性）：标准答案中的陈述，但回答中未出现
                每条陈述只属于一个类别。

                问题：%s
                回答陈述：%s
                标准答案陈述：%s

                输出 JSON（不要输出其他内容）：
                {"TP": ["陈述", ...], "FP": ["陈述", ...], "FN": ["陈述", ...]}
                """.formatted(question, answerJson, gtJson);
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
            log.warn("TP/FP/FN 解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private double semanticSimilarity(String answer, String groundTruth) {
        try {
            var a = embeddingModel.embed(answer);
            var b = embeddingModel.embed(groundTruth);
            return cosine(a, b);
        } catch (Exception e) {
            log.warn("语义相似度计算失败: {}", e.getMessage());
            return 0.0;
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Classification(Map<String, List<String>> categories) {
        int size(String key) {
            return categories.getOrDefault(key, List.of()).size();
        }
    }
}
