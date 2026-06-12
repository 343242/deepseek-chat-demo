package com.smart.rag.infrastructure.llm;

/**
 * Embedding 候选——包含向量维度
 */
public final class EmbeddingCandidate extends AbstractModelCandidate {

    private int dimension;

    @Override public int dimension() { return dimension; }

    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
}
