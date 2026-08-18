package com.smart.rag.evaluation.metrics.generation;

import com.smart.rag.evaluation.util.JsonExtractorUtil;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 上下文召回率评分器（Context Recall）
 * <p>
 * 衡量检索到的上下文是否足够完整地覆盖了标准答案的所有要点。
 * 方向与 Faithfulness 相反：标准答案 claims → context 支撑。
 * 复用 Faithfulness 的两步流程，只是将 answer 替换为 ground_truth_answer。
 * </p>
 * <p>
 * 哨兵值约定：Judge 失败/解析失败 → -1；标准答案本身无声明 → 1.0。
 * </p>
 */
@Component
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
     * @return 上下文召回率（0-1），Judge 失败返回 -1，标准答案无声明返回 1.0
     */
    public double score(String groundTruthAnswer, String context) {
        Optional<List<String>> claimsOpt = extractClaims(groundTruthAnswer);
        if (claimsOpt.isEmpty()) {
            return -1; // Judge 失败
        }
        List<String> claims = claimsOpt.get();
        if (claims.isEmpty()) {
            log.debug("No claims extracted from ground truth answer (genuinely claim-free)");
            return 1.0;
        }
        return verifyClaims(claims, context);
    }

    /**
     * @return 失败返回 empty；成功返回 of(list)，list 可能为空（标准答案本身无声明）
     */
    private Optional<List<String>> extractClaims(String text) {
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
        if (!verdict.success()) {
            log.warn("Failed to extract claims from ground truth: {}", verdict.errorMessage());
            return Optional.empty();
        }

        try {
            String json = JsonExtractorUtil.extractJson(verdict.rawJson());
            List<String> claims = objectMapper.readValue(json, new TypeReference<>() {});
            return Optional.of(new ArrayList<>(claims));
        } catch (Exception e) {
            log.warn("Failed to parse extracted claims: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 始终本地计算 supported/total，不信任 Judge 自报的汇总分数。
     */
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

                输出 JSON（只包含 verifications 数组，不要输出汇总分数）：
                {
                  "verifications": [
                    {"claim": "...", "supported": true},
                    {"claim": "...", "supported": false}
                  ]
                }
                """.formatted(context, claimsJson);

        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Failed to verify claims for context recall: {}", verdict.errorMessage());
            return -1;
        }

        try {
            String json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> verifications = (List<Map<String, Object>>) result.get("verifications");
            if (verifications == null || verifications.isEmpty()) {
                log.warn("Judge returned no verifications for {} claims", claims.size());
                return -1;
            }
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
