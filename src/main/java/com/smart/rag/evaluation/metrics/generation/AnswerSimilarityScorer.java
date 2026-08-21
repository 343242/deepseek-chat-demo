package com.smart.rag.evaluation.metrics.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 答案语义相似度评分器（翻译 ragas AnswerSimilarity / SemanticSimilarity）：
 * response 与 reference 分别 embed 后算 cosine（纯 embedding，零 LLM）。
 * 亦即 AnswerCorrectness 中语义分量的独立指标化——廉价、确定性的整体质量基线。
 * 空串以 " " 兜底（对齐 ragas 的空值替换）。哨兵：embedding 失败 -1。
 */
@Component
public class AnswerSimilarityScorer {

    private static final Logger log = LoggerFactory.getLogger(AnswerSimilarityScorer.class);

    private final EmbeddingModel embeddingModel;

    public AnswerSimilarityScorer(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * @param answer        生成的回答
     * @param groundTruth   标准答案
     * @return 语义相似度（-1-1，通常 0-1），embedding 失败 -1
     */
    public double score(String answer, String groundTruth) {
        try {
            var a = answer == null || answer.isEmpty() ? " " : answer;
            var b = groundTruth == null || groundTruth.isEmpty() ? " " : groundTruth;
            return VectorMathUtil.cosine(embeddingModel.embed(a), embeddingModel.embed(b));
        } catch (Exception e) {
            log.warn("语义相似度计算失败: {}", e);
            return -1;
        }
    }
}
