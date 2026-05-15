package com.demo.chat.rag.evaluation.metrics.retrieval;

import java.util.List;
import java.util.Set;

/**
 * 检索指标计算结果
 *
 * @param recall            Recall@K
 * @param precision         Precision@K
 * @param mrr               Mean Reciprocal Rank
 * @param ndcg              Normalized DCG@K
 * @param contextPrecision  上下文精确率（RAGAS 对齐）
 */
public record RetrievalMetrics(
        double recall,
        double precision,
        double mrr,
        double ndcg,
        double contextPrecision
) {}
