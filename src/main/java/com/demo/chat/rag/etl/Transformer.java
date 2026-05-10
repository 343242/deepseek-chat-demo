package com.demo.chat.rag.etl;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * ETL Transform 阶段：对文档进行分块处理
 */
public interface Transformer {

    /**
     * 对文档列表进行分块变换
     *
     * @param documents       原始文档列表
     * @param sourceFileName  来源文件名
     * @return 分块后的文档列表
     */
    List<Document> transform(List<Document> documents, String sourceFileName);
}
