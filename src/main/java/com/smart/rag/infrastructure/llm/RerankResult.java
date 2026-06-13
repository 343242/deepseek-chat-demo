package com.smart.rag.infrastructure.llm;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

import java.util.Objects;

/**
 * 重排序结果
 */
public record RerankResult(
    /** 在原始文档列表中的索引 */
    int originalIndex,
    /** 重排序得分（越高越相关） */
    double score,
    /** 文档内容 */
    String document
) {
    public RerankResult {
        if (document == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "文档内容不能为空");
        }
        if (originalIndex < 0) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "文档索引不能为负数: " + originalIndex);
        }
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "文档得分无效: " + score);
        }
    }
}
