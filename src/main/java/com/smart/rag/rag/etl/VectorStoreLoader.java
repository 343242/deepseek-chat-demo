package com.smart.rag.rag.etl;

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
        if (documents == null || documents.isEmpty()) {
            return;
        }
        // 幂等性：先删除同 documentId 的既有向量再写入（delete-before-load）。
        // WHY：消息总线 redelivery / 部分失败重试会导致同一文档被重复 load，
        // 而 vectorStore.add 是追加语义——不先删会产生重复向量。此层实现幂等
        // 使 ANY 重投递（含 PARTIAL 失败后的总线重试）安全，无需依赖上游状态守卫。
        documents.stream()
                .map(d -> (String) d.getMetadata().get("documentId"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(documentId ->
                        vectorStore.delete(new FilterExpressionBuilder()
                                .eq("documentId", documentId).build()));
        vectorStore.add(documents);
        log.info("Loaded {} documents into vector store", documents.size());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        vectorStore.delete(buildDocumentIdFilter(documentId));
        log.info("Deleted vectors for documentId={}", documentId);
    }

    private Filter.Expression buildDocumentIdFilter(Long documentId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.eq("documentId", String.valueOf(documentId)).build();
    }
}
