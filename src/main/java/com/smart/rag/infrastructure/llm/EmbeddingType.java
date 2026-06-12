package com.smart.rag.infrastructure.llm;

/**
 * 向量嵌入类型
 * <p>
 * 区分检索时的查询向量和入库时的文档向量。
 * 部分模型（如百炼 text-embedding-v4）对两者使用不同的编码策略。
 */
public enum EmbeddingType {
    /** 检索查询 */
    QUERY,
    /** 文档索引 */
    DOCUMENT
}
