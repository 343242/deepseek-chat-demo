package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 事实正确性评分器（翻译 ragas FactualCorrectness 完整算法）：
 * <ol>
 *   <li>分解 reference 与 response 各自的原子主张</li>
 *   <li>双向 NLI：response 主张 vs reference（tp/fp），reference 主张 vs response（fn；
 *       precision 模式跳过该方向）</li>
 *   <li>mode=precision: tp/(tp+fp+1e-8)；recall: tp/(tp+fn+1e-8)；
 *       f1: F-beta（P=tp/(tp+fp)、R=tp/(tp+fn)，P=R=0 时取 0）</li>
 *   <li>结果保留 2 位小数（对齐 ragas np.round(score, 2)）</li>
 * </ol>
 * 哨兵约定：Judge 失败 -1。分解为空时按 ragas 语义计 0（tp=fp=fn=0 → 0 分，非满分）。
 * </p>
 */
@Component
public class FactualCorrectnessScorer {

    private static final Logger log = LoggerFactory.getLogger(FactualCorrectnessScorer.class);

    /** ragas 分母 epsilon */
    private static final double EPSILON = 1e-8;

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;
    private final EvaluationProperties props;

    public FactualCorrectnessScorer(LlmJudge judge, ObjectMapper objectMapper,
                                    EvaluationProperties props) {
        this.judge = judge;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * @param groundTruthAnswer 标准答案（reference）
     * @param answer            生成的回答（response）
     * @return 指标分数（0-1），Judge 失败 -1
     */
    public double score(String groundTruthAnswer, String answer) {
        if (groundTruthAnswer == null || groundTruthAnswer.isBlank()) {
            return -1;
        }
        var mode = props.getMetrics().getFactualCorrectness().getMode().toLowerCase(Locale.ROOT);
        double beta = props.getMetrics().getFactualCorrectness().getBeta();

        var responseClaimsOpt = decompose(answer);
        if (responseClaimsOpt.isEmpty()) {
            return -1;
        }
        List<String> responseClaims = responseClaimsOpt.get();

        // tp/fp：response 主张能否被 reference 支撑
        boolean[] responseVerdicts = verifyClaims(responseClaims, groundTruthAnswer);
        if (responseVerdicts == null) {
            return -1;
        }
        int tp = 0;
        for (boolean v : responseVerdicts) {
            if (v) {
                tp++;
            }
        }
        int fp = responseVerdicts.length - tp;

        // fn：reference 主张能否被 response 支撑（precision 模式跳过，fn=0）
        int fn = 0;
        if (!"precision".equals(mode)) {
            var referenceClaimsOpt = decompose(groundTruthAnswer);
            if (referenceClaimsOpt.isEmpty()) {
                return -1;
            }
            boolean[] referenceVerdicts = verifyClaims(referenceClaimsOpt.get(), answer);
            if (referenceVerdicts == null) {
                return -1;
            }
            for (boolean v : referenceVerdicts) {
                if (!v) {
                    fn++;
                }
            }
        }

        double score = switch (mode) {
            case "precision" -> tp / (tp + fp + EPSILON);
            case "recall" -> tp / (tp + fn + EPSILON);
            default -> fbeta(tp, fp, fn, beta);
        };
        return Math.round(score * 100.0) / 100.0;
    }

    /** F-beta（翻译 ragas metrics/utils.py fbeta_score）：P=R=0 时取 0。 */
    static double fbeta(int tp, int fp, int fn, double beta) {
        double precision = tp + fp > 0 ? (double) tp / (tp + fp) : 0.0;
        double recall = tp + fn > 0 ? (double) tp / (tp + fn) : 0.0;
        if (precision == 0.0 && recall == 0.0) {
            return 0.0;
        }
        double beta2 = beta * beta;
        return (1 + beta2) * precision * recall / (beta2 * precision + recall);
    }

    /** 原子主张分解（双向共用）。Judge/解析失败返回 empty。 */
    private Optional<List<String>> decompose(String text) {
        var prompt = GenerationPrompts.CLAIM_DECOMPOSITION.formatted(text);
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
            log.warn("主张解析失败: {}", e);
            return Optional.empty();
        }
    }

    /** 主张 vs 前提 NLI。Judge 失败或数量不匹配返回 null。 */
    private boolean[] verifyClaims(List<String> claims, String premise) {
        String claimsJson;
        try {
            claimsJson = objectMapper.writeValueAsString(claims);
        } catch (Exception e) {
            return null;
        }
        var prompt = GenerationPrompts.CLAIM_VS_PREMISE_VERIFICATION.formatted(premise, claimsJson);
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
                log.warn("主张验证数量不匹配: {} vs {}",
                        items == null ? 0 : items.size(), claims.size());
                return null;
            }
            var result = new boolean[items.size()];
            for (int i = 0; i < items.size(); i++) {
                result[i] = Boolean.TRUE.equals(items.get(i).get("supported"));
            }
            return result;
        } catch (Exception e) {
            log.warn("主张验证解析失败: {}", e);
            return null;
        }
    }
}
