package com.smart.rag.infrastructure.llm;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

import java.util.List;

/**
 * Rerank 能力契约
 */
public interface RerankCapable extends CapabilityClient {

    /** 重排序 */
    List<RerankResult> rerank(RerankRequest request);

    /** 带 topN 截断的重排序（默认客户端截断，子类可覆写为服务端截断） */
    default List<RerankResult> rerank(RerankRequest request, int topN) {
        if (topN <= 0) throw new ClientException(ClientErrorCode.BAD_REQUEST, "topN 必须大于 0，当前值: " + topN);
        return rerank(request).stream().limit(topN).toList();
    }
}
