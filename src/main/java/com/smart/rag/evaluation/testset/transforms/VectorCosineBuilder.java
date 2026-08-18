package com.smart.rag.evaluation.testset.transforms;

import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.testset.graph.Relationship;
import com.smart.rag.evaluation.testset.graph.RelationshipType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量余弦相似关系构建器（对应 ragas {@code CosineSimilarityBuilder}）。
 * <p>
 * 与 ragas 的差异仅在输入：ragas 对 chunk 摘要重新 embedding，本实现直接用
 * vector_store 现成 chunk 向量（零 embedding 调用）。阈值经配置暴露，默认 0.7，
 * 因 chunk 原文向量与摘要向量的分布不同，首跑后对照 Python 参照实现校准。
 * </p>
 */
public final class VectorCosineBuilder {

    private static final Logger log = LoggerFactory.getLogger(VectorCosineBuilder.class);

    private final double threshold;

    public VectorCosineBuilder(double threshold) {
        this.threshold = threshold;
    }

    public List<Relationship> build(List<Node> nodes) {
        var relationships = new ArrayList<Relationship>();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                var a = nodes.get(i);
                var b = nodes.get(j);
                if (a.embedding() == null || b.embedding() == null) {
                    continue;
                }
                double similarity = cosine(a.embedding(), b.embedding());
                if (similarity >= threshold) {
                    relationships.add(new Relationship(a.id(), b.id(),
                            RelationshipType.SIMILARITY, similarity, true,
                            Map.of("cosineSimilarity", similarity)));
                }
            }
        }
        return relationships;
    }

    /** 标准余弦相似度；零向量安全（返回 0）。 */
    static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            // 混合维度向量库属内部数据错误（非客户端输入），降级跳过该对而非废掉整批
            log.warn("向量维度不一致，跳过该节点对: {} vs {}", a.length, b.length);
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
