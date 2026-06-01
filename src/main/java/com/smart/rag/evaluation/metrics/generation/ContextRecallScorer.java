package com.smart.rag.evaluation.metrics.generation;

import com.smart.rag.common.util.JsonExtractor;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 上下文召回率评分器（Context Recall）
 * <p>
 * 衡量检索到的上下文是否足够完整地覆盖了标准答案的所有要点。
 * 方向与 Faithfulness 相反：标准答案 claims → context 支撑。
 * 复用 Faithfulness 的两步流程，只是将 answer 替换为 ground_truth_answer。
 * </p>
 */
@Component
@Profile("evaluation")
public class ContextRecallScorer {

    private static final Logger log = LoggerFactory.getLogger(ContextRecallScorer.class);

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public ContextRecallScorer(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算上下文召回率
     *
     * @param groundTruthAnswer 标准答案
     * @param context           检索到的上下文
     * @return 上下文召回率（0-1），Judge 失败返回 -1
     */
    public double score(String groundTruthAnswer, String context) {
        // Step 1: 从标准答案中提取 claims
        List<String> claims = extractClaims(groundTruthAnswer);
        if (claims.isEmpty()) {
            log.warn("No claims extracted from ground truth answer");
            return 1.0;
        }

        // Step 2: 验证每个 claim 是否可从 context 推导
        return verifyClaims(claims, context);
    }

    private List<String> extractClaims(String text) {
        String prompt = """
                给定以下回答，提取其中所有事实性声明。
                每个声明应是一个独立的、可验证的事实陈述。

                回答：
                %s

                输出 JSON 数组（不要输出其他内容）：
                [
                  "声明1",
                  "声明2"
                ]
                """.formatted(text);

        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) return Collections.emptyList();

        try {
            String json = JsonExtractor.extractJson(verdict.rawJson());
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse extracted claims: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private double verifyClaims(List<String> claims, String context) {
        String claimsJson;
        try {
            claimsJson = objectMapper.writeValueAsString(claims);
        } catch (Exception e) {
            return -1;
        }

        String prompt = """
                给定以下上下文（检索到的文档片段）和一组声明。
                判断每个声明是否可以从上下文中推导出来。

                上下文：
                %s

                声明：
                %s

                输出 JSON（不要输出其他内容）：
                {
                  "verifications": [
                    {"claim": "...", "supported": true},
                    {"claim": "...", "supported": false}
                  ],
                  "context_recall_score": 0.75
                }
                """.formatted(context, claimsJson);

        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) return -1;

        try {
            String json = JsonExtractor.extractJson(verdict.rawJson());
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (result.containsKey("context_recall_score")) {
                return ((Number) result.get("context_recall_score")).doubleValue();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> verifications = (List<Map<String, Object>>) result.get("verifications");
            if (verifications == null || verifications.isEmpty()) return 0;
            long supported = verifications.stream()
                    .filter(v -> Boolean.TRUE.equals(v.get("supported")))
                    .count();
            return (double) supported / verifications.size();
        } catch (Exception e) {
            log.warn("Failed to parse context recall result: {}", e.getMessage());
            return -1;
        }
    }

}
