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
 * 本地计算 usefulness≥3 的片段占比作为最终分数（范围 0-1）。
 * </p>
 */
@Component
public class ContextRelevanceScorer {

    private static final Logger log = LoggerFactory.getLogger(ContextRelevanceScorer.class);

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public ContextRelevanceScorer(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算上下文相关性
     *
     * @param question       用户问题
     * @param contextDocs    检索到的文档片段
     * @return 上下文相关性分数（0-1），Judge 失败返回 -1
     */
    public double score(String question, List<Document> contextDocs) {
        if (contextDocs == null || contextDocs.isEmpty()) return 0;

        StringBuilder chunksBuilder = new StringBuilder();
        for (int i = 0; i < contextDocs.size(); i++) {
            chunksBuilder.append("片段").append(i + 1).append("：\n");
            chunksBuilder.append(contextDocs.get(i).getText()).append("\n\n");
        }

        String prompt = """
                给定以下用户问题和检索到的文档片段，评估每个片段对回答问题的有用程度。

                示例：
                问题：什么是 RAG？
                片段1："RAG（Retrieval-Augmented Generation）是一种结合检索和生成的 AI 技术。"
                → usefulness: 5（直接包含答案）
                片段2："Transformer 架构由 Google 在 2017 年提出。"
                → usefulness: 1（与问题无关）

                ---

                问题：%s

                文档片段：
                %s

                请评估每个文档片段的有用程度。
                评分标准：
                - 5分：直接包含答案
                - 4分：高度相关，提供重要线索
                - 3分：部分相关，提供背景信息
                - 2分：轻微相关
                - 1分：完全无关

                输出 JSON（只包含 chunk_scores 数组，不要输出汇总比例）：
                {
                  "chunk_scores": [
                    {"chunk_index": 0, "usefulness": 4, "reason": "..."},
                    {"chunk_index": 1, "usefulness": 1, "reason": "..."}
                  ]
                }
                """.formatted(question, chunksBuilder.toString());

        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Context relevance judge failed: {}", verdict.errorMessage());
            return -1;
        }

        try {
            String json = JsonExtractorUtil.extractJson(verdict.rawJson());
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});

            // 始终本地计算：usefulness >= 3 的 chunk 数 / 总 chunk 数
            // 不信任 Judge 自报的汇总比例，避免 LLM 估值偏差
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chunkScores = (List<Map<String, Object>>) result.get("chunk_scores");
            if (chunkScores == null || chunkScores.isEmpty()) {
                log.warn("Judge returned no chunk_scores for {} chunks", contextDocs.size());
                return -1;
            }

            long useful = chunkScores.stream()
                    .filter(c -> parseUsefulness(c) >= 3)
                    .count();
            return (double) useful / chunkScores.size();
        } catch (Exception e) {
            log.warn("Failed to parse context relevance result: {}", e.getMessage());
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
