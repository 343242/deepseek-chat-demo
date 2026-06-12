package com.smart.rag.infrastructure.llm;

import java.util.List;

/**
 * 重排序请求
 * <p>
 * 不包含 topN 字段——截断由 {@link RerankCapable#rerank(RerankRequest, int)} 重载方法处理。
 */
public record RerankRequest(
    /** 检索查询文本 */
    String query,
    /** 候选文档列表 */
    List<String> documents
) {}
