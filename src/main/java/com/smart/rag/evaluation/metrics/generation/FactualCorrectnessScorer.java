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
 * 事实正确性评分器（翻译 ragas FactualCorrectness，legacy 路径）：
 * 标准答案分解为原子主张 → 逐主张对照生成回答验证（NLI）→ F-beta（默认 β=1，召回倾向）。
 * tp=被回答支撑的主张，fn=未支撑的主张，fp=0（以参考主张为全集）。
 * 哨兵约定：Judge 失败 -1；标准答案无主张 1.0。
 */
@Component
public class FactualCorrectnessScorer {

    private static final Logger log = LoggerFactory.getLogger(FactualCorrectnessScorer.class);

    private static final double BETA = 1.0;

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public FactualCorrectnessScorer(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * @param groundTruthAnswer 标准答案
     * @param answer            生成的回答
     * @return F-beta 分数（0-1），Judge 失败 -1
     */
    public double score(String groundTruthAnswer, String answer) {
        if (groundTruthAnswer == null || groundTruthAnswer.isBlank()) {
            return -1;
        }
        var claims = decomposeGroundTruth(groundTruthAnswer);
        if (claims.isEmpty()) {
            return -1;
        }
        if (claims.get().isEmpty()) {
            return 1.0;
        }
        int[] verdicts = verifyClaimsAgainstAnswer(claims.get(), answer);
        if (verdicts == null) {
            return -1;
        }
        int tp = 0;
        for (int v : verdicts) {
            tp += v;
        }
        int fn = verdicts.length - tp;
        double beta2 = BETA * BETA;
        return (1 + beta2) * tp / ((1 + beta2) * tp + beta2 * fn);
    }

    private Optional<List<String>> decomposeGroundTruth(String groundTruth) {
        var prompt = """
                把以下标准答案分解为原子主张列表：每条主张是一个独立的、最小粒度的事实陈述。

                标准答案：
                %s

                输出 JSON 数组（不要输出其他内容）：
                ["主张1", "主张2"]
                """.formatted(groundTruth);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("主张分解失败: {}", verdict.errorMessage());
            return Optional.empty();
        }
        try {
            var json = JsonExtractorUtil.extractJson(verdict.rawJson());
            return Optional.of(new ArrayList<>(
                    objectMapper.readValue(json, new TypeReference<List<String>>() {
                    })));
        } catch (Exception e) {
            log.warn("主张解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private int[] verifyClaimsAgainstAnswer(List<String> claims, String answer) {
        String claimsJson;
        try {
            claimsJson = objectMapper.writeValueAsString(claims);
        } catch (Exception e) {
            return null;
        }
        var prompt = """
                给定一个回答与一组主张。逐主张判断：该主张能否从回答中直接推导出来。

                回答：
                %s

                主张：
                %s

                输出 JSON（不要输出其他内容）：
                {"verifications": [{"index": 1, "supported": true, "reason": "..."}, ...]}
                """.formatted(answer, claimsJson);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("主张验证失败: {}", verdict.errorMessage());
            return null;
        }
        try {
            var json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) parsed.get("verifications");
            if (items == null || items.size() != claims.size()) {
                log.warn("主张验证数量不匹配: {} vs {}", items == null ? 0 : items.size(), claims.size());
                return null;
            }
            var result = new int[items.size()];
            for (int i = 0; i < items.size(); i++) {
                result[i] = Boolean.TRUE.equals(items.get(i).get("supported")) ? 1 : 0;
            }
            return result;
        } catch (Exception e) {
            log.warn("主张验证解析失败: {}", e.getMessage());
            return null;
        }
    }
}
