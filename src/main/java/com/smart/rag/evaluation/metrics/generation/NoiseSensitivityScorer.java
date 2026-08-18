package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 噪声敏感度评分器（翻译 ragas NoiseSensitivity，默认 focus=irrelevant，legacy 路径）。
 * <p>
 * 单条样本上的判定逻辑（无扰动重生成版本的忠实翻译）：
 * <ul>
 *   <li>relevant[i]：片段 i 对回答该问题是否有用</li>
 *   <li>faithful[i]：仅凭片段 i 能否支撑生成的回答</li>
 *   <li>incorrect：回答相对标准答案存在未支撑主张（回答不正确）</li>
 * </ul>
 * irrelevant_retrieved = !relevant；score = 1（存在"无关片段支撑了回答 且 回答不正确"）
 * 或 0——数据集级均值才是 ragas 的 noise_sensitivity。哨兵：Judge 失败 -1；无片段 0。
 * </p>
 */
@Component
public class NoiseSensitivityScorer {

    private static final Logger log = LoggerFactory.getLogger(NoiseSensitivityScorer.class);

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public NoiseSensitivityScorer(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * @param question          用户问题
     * @param answer            生成的回答
     * @param groundTruthAnswer 标准答案
     * @param contextDocs       检索到的文档片段
     * @return 噪声敏感度（0 或 1），Judge 失败 -1，无片段 0
     */
    public double score(String question, String answer, String groundTruthAnswer,
                        List<Document> contextDocs) {
        if (contextDocs == null || contextDocs.isEmpty()) {
            return 0;
        }
        boolean[] relevant = new boolean[contextDocs.size()];
        boolean[] faithful = new boolean[contextDocs.size()];
        if (!judgeChunkVerdicts(question, answer, contextDocs, relevant, faithful)) {
            return -1;
        }
        Boolean incorrect = judgeIncorrect(answer, groundTruthAnswer);
        if (incorrect == null) {
            return -1;
        }

        boolean irrelevantFaithful = false;
        for (int i = 0; i < relevant.length; i++) {
            if (!relevant[i] && faithful[i]) {
                irrelevantFaithful = true;
                break;
            }
        }
        return irrelevantFaithful && incorrect ? 1.0 : 0.0;
    }

    private boolean judgeChunkVerdicts(String question, String answer,
                                       List<Document> contextDocs,
                                       boolean[] relevant, boolean[] faithful) {
        var sb = ContextTextBuilder.build(contextDocs);
        var prompt = GenerationPrompts.CHUNK_DUAL_VERDICT.formatted(question, answer, sb);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("NoiseSensitivity 片段判决失败: {}", verdict.errorMessage());
            return false;
        }
        try {
            var json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) parsed.get("chunks");
            if (items == null || items.size() != contextDocs.size()) {
                log.warn("NoiseSensitivity 判决数量不匹配: {} vs {}",
                        items == null ? 0 : items.size(), contextDocs.size());
                return false;
            }
            for (int i = 0; i < items.size(); i++) {
                relevant[i] = Boolean.TRUE.equals(items.get(i).get("useful"));
                faithful[i] = Boolean.TRUE.equals(items.get(i).get("supports_answer"));
            }
            return true;
        } catch (Exception e) {
            log.warn("NoiseSensitivity 解析失败: {}", e);
            return false;
        }
    }

    /** 回答相对标准答案是否"不正确"：存在标准答案主张未被回答覆盖即不正确。失败返回 null。 */
    private Boolean judgeIncorrect(String answer, String groundTruthAnswer) {
        if (groundTruthAnswer == null || groundTruthAnswer.isBlank()) {
            return null;
        }
        var prompt = GenerationPrompts.ANSWER_CORRECTNESS_CHECK.formatted(answer, groundTruthAnswer);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("NoiseSensitivity 正确性判决失败: {}", verdict.errorMessage());
            return null;
        }
        try {
            var json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            return !Boolean.TRUE.equals(parsed.get("correct"));
        } catch (Exception e) {
            log.warn("NoiseSensitivity 正确性解析失败: {}", e);
            return null;
        }
    }
}
