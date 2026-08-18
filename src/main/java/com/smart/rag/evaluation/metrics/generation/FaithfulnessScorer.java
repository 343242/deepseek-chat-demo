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
 * 忠实度评分器（Faithfulness）
 * <p>
 * 衡量生成的回答是否仅基于检索到的上下文，没有"幻觉"。
 * 两步分离法（对齐 RAGAS）：
 * <ol>
 *   <li>Step 1 — Claims 提取：从 answer 中提取所有独立声明</li>
 *   <li>Step 2 — Claims 验证：对每个 claim 判断是否可从 context 中推导</li>
 * </ol>
 * Faithfulness = 可推导的 claims 数 / 总 claims 数（范围 0-1）
 * </p>
 * <p>
 * 哨兵值约定：
 * <ul>
 *   <li>Judge 调用或 JSON 解析失败 → 返回 -1（评测无效，聚合时应过滤）</li>
 *   <li>答案本身不含任何声明（真空）→ 返回 1.0（无声明即无幻觉）</li>
 * </ul>
 * </p>
 */
@Component
public class FaithfulnessScorer {

    private static final Logger log = LoggerFactory.getLogger(FaithfulnessScorer.class);

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public FaithfulnessScorer(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算忠实度
     *
     * @param answer  LLM 生成的回答
     * @param context 检索到的上下文
     * @return 忠实度分数（0-1），Judge 失败返回 -1，答案无声明返回 1.0
     */
    public double score(String answer, String context) {
        // Step 1: 提取 claims（失败返回 empty，真空返回 of(emptyList)）
        Optional<List<String>> claimsOpt = extractClaims(answer);
        if (claimsOpt.isEmpty()) {
            return -1; // Judge 失败
        }
        List<String> claims = claimsOpt.get();
        if (claims.isEmpty()) {
            log.debug("No claims extracted from answer (genuinely claim-free)");
            return 1.0; // 答案本身无声明 = 无幻觉 = 满分
        }

        // Step 2: 验证 claims
        return verifyClaims(claims, context);
    }

    /**
     * Step 1: 从回答中提取所有事实性声明
     *
     * @return 失败返回 empty；成功返回 of(list)，list 可能为空（答案本身无声明）
     */
    private Optional<List<String>> extractClaims(String answer) {
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
                """.formatted(answer);

        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Failed to extract claims: {}", verdict.errorMessage());
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
     * Step 2: 验证每个 claim 是否可从 context 推导
     * <p>
     * 始终本地计算 supported/total，不信任 Judge 自报的汇总分数。
     */
    private double verifyClaims(List<String> claims, String context) {
        String claimsJson;
        try {
            claimsJson = objectMapper.writeValueAsString(claims);
        } catch (Exception e) {
            log.warn("Failed to serialize claims: {}", e.getMessage());
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
                    {"claim": "...", "supported": true, "evidence": "上下文中提到..."},
                    {"claim": "...", "supported": false, "reason": "上下文中未提及..."}
                  ]
                }
                """.formatted(context, claimsJson);

        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Failed to verify claims: {}", verdict.errorMessage());
            return -1;
        }

        try {
            String json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> verifications = (List<Map<String, Object>>) result.get("verifications");
            // Judge 返回了结构但 verifications 缺失/为空 = 解析失败语义（claims 非空却无验证结果）
            if (verifications == null || verifications.isEmpty()) {
                log.warn("Judge returned no verifications for {} claims", claims.size());
                return -1;
            }

            long supported = verifications.stream()
                    .filter(v -> Boolean.TRUE.equals(v.get("supported")))
                    .count();
            return (double) supported / verifications.size();
        } catch (Exception e) {
            log.warn("Failed to parse verification result: {}", e.getMessage());
            return -1;
        }
    }
}
