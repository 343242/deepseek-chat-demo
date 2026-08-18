package com.smart.rag.evaluation.metrics.generation;

/**
 * 生成侧指标
 *
 * @param faithfulness        忠实度（answer claims → context 支撑）
 * @param contextRecall       上下文召回率（ground_truth claims → context 支撑）
 * @param answerRelevance     回答相关性（embedding cosine 相似度）
 * @param contextRelevance    上下文相关性（LLM-as-Judge）
 * @param answerCorrectness   答案正确性（TP/FP/FN F1 + 语义相似度加权，ragas 0.4.3）
 * @param noiseSensitivity    噪声敏感度（无关片段支撑错误回答，ragas 0.4.3）
 * @param contextPrecisionLlm 上下文精度 LLM 版（平均精度，ragas 0.4.3）
 * @param factualCorrectness  事实正确性（主张分解 + NLI F-beta，ragas 0.4.3）
 * @param rougeL              Rouge-L（字符级 LCS F1，确定性零 LLM）
 * @param bleu                BLEU（字符级 1-4 gram，确定性零 LLM）
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
        double bleu
) {
}
