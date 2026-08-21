package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 声明提取 + 验证的两步 LLM 流程（Faithfulness / ContextRecall 共用，原先两份逐行复制）。
 * <p>
 * 始终本地计算 supported/total，不信任 Judge 自报的汇总分数；
 * 并对 LLM 返回的 verifications 数量与 claims 数量对账——不匹配判无效（-1），
 * 与 ContextPrecisionLlmScorer / FactualCorrectnessScorer 的做法一致。
 * </p>
 */
@Component
public class ClaimVerificationSupport {

    private static final Logger log = LoggerFactory.getLogger(ClaimVerificationSupport.class);

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public ClaimVerificationSupport(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * Step 1: 从文本中提取所有事实性声明。
     *
     * @return Judge/解析失败返回 empty；成功返回 of(list)，list 可能为空（文本本身无声明）
     */
    public Optional<List<String>> extractClaims(String text) {
        var prompt = GenerationPrompts.CLAIM_EXTRACTION.formatted(text);

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
            log.warn("Failed to parse extracted claims: {}", e);
            return Optional.empty();
        }
    }

    /**
     * Step 2: 验证每个 claim 是否可从 context 推导（聚合版）。
     *
     * @return supported / total（0-1）；Judge 失败/数量不匹配返回 -1
     */
    public double verifyClaims(List<String> claims, String context) {
        boolean[] verdicts = verifyClaimVerdicts(claims, context);
        if (verdicts == null) {
            return -1;
        }
        long supported = 0;
        for (boolean v : verdicts) {
            if (v) {
                supported++;
            }
        }
        return (double) supported / verdicts.length;
    }

    /**
     * Step 2 的逐主张版本（NoiseSensitivity 语句级矩阵需要每个 claim 的独立判决）。
     *
     * @return 与 claims 等长的布尔数组（true=可推导）；Judge 失败/数量不匹配返回 null
     */
    public boolean[] verifyClaimVerdicts(List<String> claims, String context) {
        String claimsJson;
        try {
            claimsJson = objectMapper.writeValueAsString(claims);
        } catch (Exception e) {
            log.warn("Failed to serialize claims: {}", e);
            return null;
        }

        var prompt = GenerationPrompts.CLAIM_VERIFICATION.formatted(context, claimsJson);

        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Failed to verify claims: {}", verdict.errorMessage());
            return null;
        }

        try {
            String json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> verifications = (List<Map<String, Object>>) result.get("verifications");
            // verifications 缺失/为空，或数量与 claims 不匹配（LLM 少回/多回会改变分母）= 评测无效
            if (verifications == null || verifications.isEmpty()
                    || verifications.size() != claims.size()) {
                log.warn("Judge returned {} verifications for {} claims",
                        verifications == null ? 0 : verifications.size(), claims.size());
                return null;
            }

            var verdictArray = new boolean[verifications.size()];
            for (int i = 0; i < verifications.size(); i++) {
                verdictArray[i] = Boolean.TRUE.equals(verifications.get(i).get("supported"));
            }
            return verdictArray;
        } catch (Exception e) {
            log.warn("Failed to parse verification result: {}", e);
            return null;
        }
    }
}
