package com.smart.rag.evaluation.metrics.generation;

/**
 * 生成侧指标
 *
 * @param faithfulness        忠实度（answer claims → context 支撑）
 * @param contextRecall       上下文召回率（ground_truth claims → context 支撑）
 * @param answerRelevance     回答相关性（反向问题采样 cosine 均值 × noncommittal 乘子）
 * @param contextRelevance    上下文相关性（LLM-as-Judge）
 * @param answerCorrectness   答案正确性（TP/FP/FN F-beta + 语义相似度加权，ragas 0.4.3）
 * @param noiseSensitivity    噪声敏感度（语句级矩阵：错误主张被相关/无关片段支撑，ragas 0.4.3）
 * @param contextPrecisionLlm 上下文精度 LLM 版（平均精度，ragas 0.4.3）
 * @param factualCorrectness  事实正确性（双向主张分解 + NLI，mode/β 可配，ragas 0.4.3）
 * @param rougeL              Rouge-L（字符级 LCS F1，确定性零 LLM）
 * @param bleu                BLEU（字符级 1-4 gram，确定性零 LLM）
 * @param answerSimilarity    答案语义相似度（response vs reference embedding cosine，ragas 0.4.3）
 * @param contextEntityRecall 上下文实体召回（参考实体 ∩ 上下文实体 / 参考实体，ragas 0.4.3）
 * @param contextUtilization  上下文利用率（reference-free 平均精度，ragas 0.4.3）
 */
public record GenerationMetrics(
        double faithfulness,
        double contextRecall,
        double answerRelevance,
        double contextRelevance,
        double answerCorrectness,
        double noiseSensitivity,
        double contextPrecisionLlm,
        double factualCorrectness,
        double rougeL,
        double bleu,
        double answerSimilarity,
        double contextEntityRecall,
        double contextUtilization
) {
}
