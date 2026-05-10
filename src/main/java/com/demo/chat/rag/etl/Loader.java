package com.demo.chat.rag.etl;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * ETL Load 阶段：将处理后的文档写入向量存储
 */
public interface Loader {

    /**
     * 将文档写入目标存储
     *
     * @param documents 待写入的文档列表
     */
    void load(List<Document> documents);

    /**
     * 按文档 ID 删除关联的所有向量数据
     *
     * @param documentId 文档 ID
     */
    void deleteByDocumentId(Long documentId);
}
