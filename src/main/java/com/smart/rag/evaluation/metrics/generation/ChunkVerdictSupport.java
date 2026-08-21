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
 * 逐片段二值判决的共享支撑（ContextPrecisionLlm 与 ContextUtilization 共用，
 * 仅判决对象不同：参考答案 vs 生成回答）。
 * <p>
 * 始终本地计算，不信任 Judge 自报汇总；返回条目数与片段数对账，不匹配判无效。
 * </p>
 */
@Component
public class ChunkVerdictSupport {

    private static final Logger log = LoggerFactory.getLogger(ChunkVerdictSupport.class);

    private final LlmJudge judge;
    private final ObjectMapper objectMapper;

    public ChunkVerdictSupport(LlmJudge judge, ObjectMapper objectMapper) {
        this.judge = judge;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量片段判决。
     *
     * @param promptTemplate 判决 prompt 模板（占位：问题、判决对象文本、片段拼接）
     * @param question       用户问题
     * @param answerText     判决对象（参考答案或生成回答）
     * @return 与 contextDocs 等长的 0/1 数组；Judge 失败/数量不匹配返回 null
     */
    public int[] verdicts(String promptTemplate, String question, String answerText,
                          List<Document> contextDocs) {
        var sb = new StringBuilder(ContextTextBuilder.build(contextDocs));
        var prompt = promptTemplate.formatted(question, answerText, sb);
        var verdict = judge.evaluate(prompt);
        if (!verdict.success()) {
            log.warn("片段判决失败: {}", verdict.errorMessage());
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
                log.warn("片段判决数量不匹配: {} vs {}", items == null ? 0 : items.size(),
                        contextDocs.size());
                return null;
            }
            var result = new int[items.size()];
            for (int i = 0; i < items.size(); i++) {
                result[i] = parseVerdict(items.get(i).get("verdict"));
            }
            return result;
        } catch (Exception e) {
            log.warn("片段判决解析失败: {}", e);
            return null;
        }
    }

    /**
     * Average Precision（翻译 ragas _calculate_average_precision）：
     * 相关片段排名越靠前分数越高；全不相关 → 0。
     */
    public static double averagePrecision(int[] verdicts) {
        int relevantTotal = 0;
        for (int v : verdicts) {
            relevantTotal += v;
        }
        if (relevantTotal == 0) {
            return 0;
        }
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
