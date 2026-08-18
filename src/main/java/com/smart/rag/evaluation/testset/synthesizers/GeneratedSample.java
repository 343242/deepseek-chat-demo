package com.smart.rag.evaluation.testset.synthesizers;

import java.util.List;

/**
 * 合成器产出的测试集样本（对应 ragas SingleTurnSample 的落库字段）。
 *
 * @param userInput         问题
 * @param reference         参考答案
 * @param referenceContexts 参考依据的 chunk 原文（多跳为多个，带 hop 标签）
 * @param relevantChunkIds  参考依据的 chunk id（入库 relevant_chunk_ids）
 * @param personaName       出题 persona 名
 * @param queryStyle        问题风格
 * @param queryLength       问题长度
 * @param synthesizerName   产出该样本的合成器名
 */
public record GeneratedSample(
        String userInput,
        String reference,
        List<String> referenceContexts,
        List<String> relevantChunkIds,
        String personaName,
        String queryStyle,
        String queryLength,
        String synthesizerName) {
}
