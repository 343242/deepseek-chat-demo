package com.demo.chat.rag.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 文档分块服务接口
 * <p>
 * 将解析后的长文档按 token 数切分为适当大小的 chunk，
 * 并附加元数据（source、chunkIndex、totalChunks）。
 * </p>
 */
public interface DocumentChunkService {

    /**
     * 对文档列表进行分块
     *
     * @param documents 原始文档列表
     * @param sourceFileName 来源文件名（用于元数据）
     * @return 分块后的文档列表
     */
    List<Document> chunk(List<Document> documents, String sourceFileName);
}
