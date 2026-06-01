package com.smart.rag.evaluation.metrics.retrieval;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索指标计算器
 * <p>
 * 计算 Recall@K, Precision@K, MRR, NDCG, Context Precision。
 * 聚合模式：micro-average（与 RAGAS 一致）。
 * </p>
 */
@Component
@Profile("evaluation")
public class RetrievalMetricsCalculator {

    /**
     * 计算单条查询的检索指标
     *
     * @param retrievedIds 检索到的文档 ID 列表（按相关性排序）
     * @param relevantIds  ground truth 中相关的文档 ID 集合
     * @param k            截断位置
     * @return 检索指标
     */
    public RetrievalMetrics calculate(List<String> retrievedIds, Set<String> relevantIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty()) {
            return new RetrievalMetrics(0, 0, 0, 0, 0);
        }
        if (retrievedIds == null || retrievedIds.isEmpty()) {
            return new RetrievalMetrics(0, 0, 0, 0, 0);
        }

        List<String> topK = retrievedIds.subList(0, Math.min(k, retrievedIds.size()));

        double recall = computeRecall(topK, relevantIds);
        double precision = computePrecision(topK, relevantIds);
        double mrr = computeMRR(retrievedIds, relevantIds);
        double ndcg = computeNDCG(retrievedIds, relevantIds, k);
        double contextPrecision = computeContextPrecision(topK, relevantIds);

        return new RetrievalMetrics(recall, precision, mrr, ndcg, contextPrecision);
    }

    /**
     * Recall@K = |检索到的相关文档 ∩ ground truth| / |ground truth|
     */
    private double computeRecall(List<String> topK, Set<String> relevantIds) {
        Set<String> hits = new HashSet<>(topK);
        hits.retainAll(relevantIds);
        return (double) hits.size() / relevantIds.size();
    }

    /**
     * Precision@K = |检索到的相关文档 ∩ ground truth| / K
     */
    private double computePrecision(List<String> topK, Set<String> relevantIds) {
        if (topK.isEmpty()) return 0;
        Set<String> hits = new HashSet<>(topK);
        hits.retainAll(relevantIds);
        return (double) hits.size() / (double) topK.size();
    }

    /**
     * MRR = 1 / rank_i，其中 rank_i 是第一个相关文档的排名
     */
    private double computeMRR(List<String> retrievedIds, Set<String> relevantIds) {
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (relevantIds.contains(retrievedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    /**
     * NDCG@K = DCG@K / IDCG@K
     * <p>
     * 简化版：相关文档 rel=1，不相关 rel=0
     * DCG@K = Σ (2^rel_i - 1) / log2(i + 2)，i 从 0 开始
     * </p>
     */
    private double computeNDCG(List<String> retrievedIds, Set<String> relevantIds, int k) {
        List<String> topK = retrievedIds.subList(0, Math.min(k, retrievedIds.size()));

        // 计算 DCG
        double dcg = 0;
        for (int i = 0; i < topK.size(); i++) {
            double rel = relevantIds.contains(topK.get(i)) ? 1.0 : 0.0;
            dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }

        // 计算 IDCG（理想排序：所有相关文档排在前面）
        int numRelevant = Math.min(relevantIds.size(), k);
        double idcg = 0;
        for (int i = 0; i < numRelevant; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }

        return idcg == 0 ? 0 : dcg / idcg;
    }

    /**
     * Context Precision（对齐 RAGAS NonLLMContextPrecisionWithReference）
     * <p>
     * 公式: Σ(k=1..K) (Precision@k × rel(k)) / Σ(k=1..K) rel(k)
     * <p>
     * 特点：如果相关文档排在前面分数高；混在噪声中分数低。
     * 与 NDCG 的区别：更关注"相关文档是否排在前面"这一 RAG 特定问题。
     */
    private double computeContextPrecision(List<String> topK, Set<String> relevantIds) {
        int relevantCount = 0;
        double weightedPrecisionSum = 0;

        for (int k = 0; k < topK.size(); k++) {
            if (relevantIds.contains(topK.get(k))) {
                relevantCount++;
                double precisionAtK = (double) relevantCount / (k + 1);
                weightedPrecisionSum += precisionAtK;
            }
        }

        return relevantCount == 0 ? 0 : weightedPrecisionSum / relevantCount;
    }
}
