package com.smart.rag.evaluation.testset.graph;

/**
 * 知识图谱关系边类型。
 * <ul>
 *   <li>{@link #ENTITY_OVERLAP}：两 chunk 的实体经 Jaro-Winkler 模糊匹配重叠（多跳具体题的原料，对应 ragas entities_overlap）</li>
 *   <li>{@link #SIMILARITY}：两 chunk 的现成向量余弦相似度过阈（多跳抽象题的原料，对应 ragas summary_similarity）</li>
 * </ul>
 */
public enum RelationshipType {
    ENTITY_OVERLAP,
    SIMILARITY
}
