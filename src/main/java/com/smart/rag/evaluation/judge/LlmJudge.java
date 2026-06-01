package com.smart.rag.evaluation.judge;

import java.util.List;

/**
 * LLM-as-Judge 接口
 * <p>
 * 使用独立的 Judge 模型进行生成侧指标评估。
 * 所有评分归一化到 0-1 范围。
 * </p>
 */
public interface LlmJudge {

    /**
     * 调用 Judge 模型并返回结构化评估结果
     *
     * @param prompt 评估 prompt
     * @return Judge 评估结果
     */
    JudgeVerdict evaluate(String prompt);

    /**
     * 从回答中反向生成可能的问题（用于 Answer Relevance 计算）
     *
     * @param answer 生成的回答
     * @return 生成的问题列表
     */
    List<String> generateQuestions(String answer);

    /**
     * Judge 评估结果
     */
    record JudgeVerdict(boolean success, String rawJson, String errorMessage) {
        public static JudgeVerdict ok(String rawJson) {
            return new JudgeVerdict(true, rawJson, null);
        }

        public static JudgeVerdict failed(String errorMessage) {
            return new JudgeVerdict(false, null, errorMessage);
        }
    }
}
