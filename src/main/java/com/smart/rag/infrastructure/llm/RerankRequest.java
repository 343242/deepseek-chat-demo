package com.smart.rag.infrastructure.llm;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

import java.util.List;
import java.util.Objects;

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
) {
    public RerankRequest {
        if (query == null || query.isBlank()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "查询文本不能为空");
        }
        if (documents == null || documents.isEmpty()) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "文档列表不能为空");
        }
        documents = List.copyOf(documents);
    }
}
