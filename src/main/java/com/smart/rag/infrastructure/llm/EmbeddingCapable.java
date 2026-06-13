package com.smart.rag.infrastructure.llm;

import java.util.List;

/**
 * Embedding 能力契约
 */
public interface EmbeddingCapable extends CapabilityClient {

    /** 单条文本向量嵌入 */
    float[] embed(String text, EmbeddingType type);

    /**
     * 批量文本向量嵌入
     * <p><b>警告</b>：默认实现逐条调用 {@link #embed}，性能为 O(n) 次 HTTP 请求。
     * 建议供应商实现覆写此方法以使用原生批量 API。
     *
     * @param texts 文本列表（建议不超过 20 条，超出请分批调用）
     */
    default List<float[]> embedBatch(List<String> texts, EmbeddingType type) {
        return texts.stream().map(text -> embed(text, type)).toList();
    }

    /** 向量维度 */
    int dimension();
}
