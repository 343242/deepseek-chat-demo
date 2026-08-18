package com.smart.rag.evaluation.metrics.generation;

import com.smart.rag.evaluation.util.JsonExtractorUtil;
import com.smart.rag.evaluation.judge.LlmJudge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 上下文相关性评分器（Context Relevance）
 * <p>
 * 衡量检索到的上下文对回答问题的有用程度。
 * LLM-as-Judge + Few-Shot 示例对每个片段打 1-5 分有用度，
 * 本地计算 usefulness≥{@value #USEFULNESS_THRESHOLD} 的片段占比作为最终分数（范围 0-1）。
 * </p>
 */
@Component
public class ContextRelevanceScorer {

    private static final Logger log = LoggerFactory.getLogger(ContextRelevanceScorer.class);

    /** 片段有用度判定阈值：评分 ≥ 3 记为有用（部分相关以上） */
    static final int USEFULNESS_THRESHOLD = 3;

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public ContextRelevanceScorer(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算上下文相关性
     *
     * @param question    用户问题
     * @param contextDocs 检索到的文档片段
     * @return 上下文相关性分数（0-1），Judge 失败返回 -1
     */
    public double score(String question, List<Document> contextDocs) {
        if (contextDocs == null || contextDocs.isEmpty()) return 0;

        var verdict = judge.evaluate(buildPrompt(question, contextDocs));
        if (!verdict.success()) {
            log.warn("Context relevance judge failed: {}", verdict.errorMessage());
            return -1;
        }
        return computeScore(verdict.rawJson(), contextDocs.size());
    }

    private String buildPrompt(String question, List<Document> contextDocs) {
        return GenerationPrompts.CHUNK_USEFULNESS.formatted(question, ContextTextBuilder.build(contextDocs));
    }

    /**
     * 始终本地计算：usefulness ≥ 阈值的 chunk 数 / 总 chunk 数——
     * 不信任 Judge 自报的汇总比例，避免 LLM 估值偏差。
     * 返回条目数与片段数对账，不匹配判无效（-1），与 ContextPrecisionLlmScorer 一致。
     */
    private double computeScore(String rawJson, int chunkCount) {
        try {
            String json = JsonExtractorUtil.extractJson(rawJson);
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chunkScores = (List<Map<String, Object>>) result.get("chunk_scores");
            if (chunkScores == null || chunkScores.isEmpty()) {
                log.warn("Judge returned no chunk_scores for {} chunks", chunkCount);
                return -1;
            }
            if (chunkScores.size() != chunkCount) {
                log.warn("Judge returned {} chunk_scores for {} chunks", chunkScores.size(), chunkCount);
                return -1;
            }

            long useful = chunkScores.stream()
                    .filter(c -> parseUsefulness(c) >= USEFULNESS_THRESHOLD)
                    .count();
            return (double) useful / chunkScores.size();
        } catch (Exception e) {
            log.warn("Failed to parse context relevance result: {}", e);
            return -1;
        }
    }

    /**
     * 健壮地提取 chunk 的 usefulness 分数。
     * 字段缺失或类型不符时返回 0（视为无用），避免单个 chunk 解析失败拖垮整体。
     */
    private double parseUsefulness(Map<String, Object> chunk) {
        Object v = chunk.get("usefulness");
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
