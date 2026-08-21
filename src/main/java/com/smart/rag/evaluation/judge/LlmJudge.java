package com.smart.rag.evaluation.judge;

import java.util.List;

/**
 * LLM-as-Judge 接口
 * <p>
 * 使用独立的 Judge 模型进行生成侧指标评估。
 * 所有评分归一化到 0-1 范围。
 * 温度策略对齐 ragas 0.4.3：单次 judge 生成 0.01（近确定性），
 * reverse-question 多采样 0.3（等价 ragas generate_multiple n&gt;1 的取温逻辑）。
 * </p>
 */
public interface LlmJudge {

    /**
     * 调用 Judge 模型并返回结构化评估结果（温度 0.01）
     *
     * @param prompt 评估 prompt
     * @return Judge 评估结果
     */
    JudgeVerdict evaluate(String prompt);

    /**
     * 从回答反向生成（问题, noncommittal) 采样（温度 0.3，strictness 次独立调用，
     * 对应 ragas ResponseRelevancy 的 strictness 多采样）
     *
     * @param answer     生成的回答
     * @param strictness 独立采样次数（ragas 默认 3）
     * @return 生成结果（失败调用不贡献条目）
     */
    List<GeneratedQuestion> generateQuestionsWithFlags(String answer, int strictness);

    /** 单次反向问题采样结果（noncommittal：回答含糊/回避/未给实质内容） */
    record GeneratedQuestion(String question, boolean noncommittal) {
    }

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
