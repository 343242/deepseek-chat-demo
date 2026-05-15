package com.demo.chat.rag.evaluation.metrics.generation;

import com.demo.chat.rag.evaluation.judge.LlmJudge;
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
 * LLM-as-Judge + Few-Shot 示例，useful_chunk_ratio 归一化到 0-1。
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

                输出 JSON（不要输出其他内容）：
                {
                  "chunk_scores": [
                    {"chunk_index": 0, "usefulness": 4, "reason": "..."},
                    {"chunk_index": 1, "usefulness": 1, "reason": "..."}
                  ],
                  "useful_chunk_ratio": 0.6
                }
                """.formatted(question, chunksBuilder.toString());

        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("Context relevance judge failed: {}", verdict.errorMessage());
            return -1;
        }

        try {
            String json = extractJson(verdict.rawJson());
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});

            // 优先使用 judge 返回的 ratio
            if (result.containsKey("useful_chunk_ratio")) {
                return ((Number) result.get("useful_chunk_ratio")).doubleValue();
            }

            // 否则手动计算：usefulness >= 3 的 chunk 数 / 总 chunk 数
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chunkScores = (List<Map<String, Object>>) result.get("chunk_scores");
            if (chunkScores == null || chunkScores.isEmpty()) return 0;

            long useful = chunkScores.stream()
                    .filter(c -> ((Number) c.get("usefulness")).doubleValue() >= 3)
                    .count();
            return (double) useful / chunkScores.size();
        } catch (Exception e) {
            log.warn("Failed to parse context relevance result: {}", e.getMessage());
            return -1;
        }
    }

    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        var matcher = java.util.regex.Pattern.compile("```json\\s*\\n([\\s\\S]*?)\\n\\s*```").matcher(raw);
        if (matcher.find()) return matcher.group(1).trim();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return trimmed;
    }
}
