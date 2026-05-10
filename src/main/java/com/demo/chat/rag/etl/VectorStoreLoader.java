package com.demo.chat.rag.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PGvector 向量存储加载器
 * <p>
 * 将分块后的文档写入 PGvector 向量数据库。
 * 支持按 documentId 删除关联的向量数据。
 * </p>
 */
@Component
public class VectorStoreLoader implements Loader {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreLoader.class);

    private final VectorStore vectorStore;

    public VectorStoreLoader(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void load(List<Document> documents) {
        vectorStore.add(documents);
        log.info("Loaded {} documents into vector store", documents.size());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.eq("documentId", String.valueOf(documentId)).build();
        vectorStore.delete(filter);
        log.info("Deleted vectors for documentId={}", documentId);
    }
}
