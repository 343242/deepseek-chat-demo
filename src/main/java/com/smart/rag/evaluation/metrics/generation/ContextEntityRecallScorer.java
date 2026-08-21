package com.smart.rag.evaluation.metrics.generation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.smart.rag.evaluation.util.JsonExtractorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文实体召回评分器（翻译 ragas ContextEntityRecall）：
 * 同一实体抽取 prompt 分别跑参考答案与拼接后的全部检索片段，
 * score = |上下文实体 ∩ 参考实体| / (|参考实体| + 1e-8)，精确字符串匹配。
 * 从"实体"粒度度量检索完整性——比 ContextRecall（逐句归因）便宜，
 * 且能单独暴露"恰好丢了最关键实体"的情况。
 * 哨兵约定：Judge 失败 -1。
 */
@Component
public class ContextEntityRecallScorer {

    private static final Logger log = LoggerFactory.getLogger(ContextEntityRecallScorer.class);

    /** ragas 分母 epsilon */
    private static final double EPSILON = 1e-8;

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public ContextEntityRecallScorer(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * @param groundTruthAnswer 标准答案（实体来源之一）
     * @param contextText       拼接后的检索上下文文本
     * @return 实体召回（0-1），Judge 失败 -1
     */
    public double score(String groundTruthAnswer, String contextText) {
        var gtEntities = extractEntities(groundTruthAnswer);
        var ctxEntities = extractEntities(contextText);
        if (gtEntities == null || ctxEntities == null) {
            return -1;
        }
        Set<String> intersection = new HashSet<>(gtEntities);
        intersection.retainAll(ctxEntities);
        return intersection.size() / (gtEntities.size() + EPSILON);
    }

    /** 实体抽取（同一 prompt 双向共用）。Judge/解析失败返回 null。 */
    private Set<String> extractEntities(String text) {
        var prompt = GenerationPrompts.ENTITY_EXTRACTION.formatted(text);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("实体抽取失败: {}", verdict.errorMessage());
            return null;
        }
        try {
            var json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            var entities = (List<String>) parsed.get("entities");
            return entities == null ? Set.of() : Set.copyOf(entities);
        } catch (Exception e) {
            log.warn("实体抽取解析失败: {}", e);
            return null;
        }
    }
}
