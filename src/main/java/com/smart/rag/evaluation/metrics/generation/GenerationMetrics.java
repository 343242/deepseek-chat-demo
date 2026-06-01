package com.smart.rag.evaluation.metrics.generation;

/**
 * 生成侧指标
 *
 * @param faithfulness       忠实度（answer claims → context 支撑）
 * @param contextRecall      上下文召回率（ground_truth claims → context 支撑）
 * @param answerRelevance    回答相关性（embedding cosine 相似度）
 * @param contextRelevance   上下文相关性（LLM-as-Judge）
 */
public record GenerationMetrics(
        double faithfulness,
        double contextRecall,
        double answerRelevance,
        double contextRelevance
) {}
