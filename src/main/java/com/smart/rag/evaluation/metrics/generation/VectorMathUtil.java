package com.smart.rag.evaluation.metrics.generation;

/**
 * 向量相似度工具。统一用 double 累加——原先两份私有 cosine 实现
 * 一份 double、一份按 float 累加，长向量下精度行为不一致。
 */
public final class VectorMathUtil {

    private VectorMathUtil() {
    }

    /**
     * 余弦相似度（0 向量返回 0）
     */
    public static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
