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
 * 上下文精度评分器 LLM 版（翻译 ragas ContextPrecision（LLMContextPrecisionWithReference），
 * legacy 路径）：逐片段判断"该片段对推导出给定（参考）答案是否有用"，按
 * 平均精度（Average Precision）聚合——相关片段排得越靠前分数越高。
 * 哨兵约定：Judge 失败 -1；无片段 0。
 */
@Component
public class ContextPrecisionLlmScorer {

    private static final Logger log = LoggerFactory.getLogger(ContextPrecisionLlmScorer.class);

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public ContextPrecisionLlmScorer(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * @param question          用户问题
     * @param groundTruthAnswer 标准答案
     * @param contextDocs       检索到的文档片段（有序，排名即输入顺序）
     * @return 平均精度（0-1），Judge 失败 -1，无片段 0
     */
    public double score(String question, String groundTruthAnswer, List<Document> contextDocs) {
        if (contextDocs == null || contextDocs.isEmpty()) {
            return 0;
        }
        var verdicts = judgeVerdicts(question, groundTruthAnswer, contextDocs);
        if (verdicts == null) {
            return -1;
        }
        int relevantTotal = 0;
        for (int v : verdicts) {
            relevantTotal += v;
        }
        if (relevantTotal == 0) {
            return 0;
        }
        // Average Precision（翻译 ragas _calculate_average_precision）
        double numerator = 0.0;
        int cumsum = 0;
        for (int i = 0; i < verdicts.length; i++) {
            cumsum += verdicts[i];
            if (verdicts[i] == 1) {
                numerator += (double) cumsum / (i + 1);
            }
        }
        return numerator / relevantTotal;
    }

    private int[] judgeVerdicts(String question, String groundTruthAnswer,
                                List<Document> contextDocs) {
        var sb = new StringBuilder(ContextTextBuilder.build(contextDocs));
        var prompt = GenerationPrompts.CHUNK_VERDICT_FOR_REFERENCE.formatted(question, groundTruthAnswer, sb);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("ContextPrecision 判决失败: {}", verdict.errorMessage());
            return null;
        }
        try {
            var json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) parsed.get("verdicts");
            if (items == null || items.size() != contextDocs.size()) {
                log.warn("ContextPrecision 判决数量不匹配: {} vs {}", items == null ? 0 : items.size(),
                        contextDocs.size());
                return null;
            }
            var result = new int[items.size()];
            for (int i = 0; i < items.size(); i++) {
                result[i] = parseVerdict(items.get(i).get("verdict"));
            }
            return result;
        } catch (Exception e) {
            log.warn("ContextPrecision 解析失败: {}", e);
            return null;
        }
    }

    private static int parseVerdict(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue() >= 1 ? 1 : 0;
        }
        if (raw instanceof String s) {
            return "1".equals(s.trim()) || "true".equalsIgnoreCase(s.trim()) ? 1 : 0;
        }
        return 0;
    }
}
