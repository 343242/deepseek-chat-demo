package com.demo.chat.rag.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.*;

/**
 * 父文档后处理器
 * <p>
 * 在向量检索命中子切分后，执行以下处理：
 * <ol>
 *   <li>识别子切分（metadata 中有 parentId 且 isParent=false）</li>
 *   <li>通过 parentId 从向量库回查父文档内容</li>
 *   <li>将子切分替换为其父文档内容（提供完整上下文）</li>
 *   <li>按 parentId 去重（同一父文档的多个子切分只保留一个父文档）</li>
 *   <li>保持原始检索顺序（按首次命中的子切分排序）</li>
 * </ol>
 *
 * <p>注意：父文档也存储在向量库中（isParent=true），子块 metadata 只存 parentId，
 * 不复制完整父文内容，避免向量库膨胀。</p>
 */
public class ParentDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ParentDocumentPostProcessor.class);

    private final VectorStore vectorStore;

    public ParentDocumentPostProcessor(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        // 收集所有需要回查的 parentId
        Set<String> parentIdsToFetch = new LinkedHashSet<>();
        List<Document> nonChildDocs = new ArrayList<>();

        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            Object isParent = metadata.get(ParentChildChunkStrategy.META_IS_PARENT);
            Object parentIdObj = metadata.get(ParentChildChunkStrategy.META_PARENT_ID);

            // 跳过已经是父文档的条目
            if (Boolean.TRUE.equals(isParent)) {
                nonChildDocs.add(doc);
                continue;
            }

            if (parentIdObj != null) {
                parentIdsToFetch.add(parentIdObj.toString());
            } else {
                nonChildDocs.add(doc);
            }
        }

        // 批量回查父文档
        Map<String, Document> parentDocMap = fetchParentDocuments(parentIdsToFetch);

        // 构建结果：子切分 → 父文档（去重，保持顺序）
        Map<String, Document> resolvedParents = new LinkedHashMap<>();
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            Object isParent = metadata.get(ParentChildChunkStrategy.META_IS_PARENT);
            if (Boolean.TRUE.equals(isParent)) continue;

            Object parentIdObj = metadata.get(ParentChildChunkStrategy.META_PARENT_ID);
            if (parentIdObj == null) continue;

            String parentId = parentIdObj.toString();
            if (resolvedParents.containsKey(parentId)) continue;

            Document parentDoc = parentDocMap.get(parentId);
            if (parentDoc != null) {
                resolvedParents.put(parentId, parentDoc);
            } else {
                log.warn("Parent document not found for parentId={}, using child chunk as fallback", parentId);
                resolvedParents.put(parentId, doc);
            }
        }

        List<Document> result = new ArrayList<>(resolvedParents.size() + nonChildDocs.size());
        result.addAll(resolvedParents.values());
        result.addAll(nonChildDocs);

        log.debug("ParentDocumentPostProcessor: {} docs → {} parent docs + {} non-child docs",
                documents.size(), resolvedParents.size(), nonChildDocs.size());

        return result;
    }

    /**
     * 从向量库批量回查父文档
     * <p>
     * 由于 VectorStore API 限制（不支持批量 metadata 查询），
     * 逐个 parentId 查询。如果性能成为瓶颈，可考虑加缓存。
     * </p>
     */
    private Map<String, Document> fetchParentDocuments(Set<String> parentIds) {
        Map<String, Document> result = new HashMap<>();
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        for (String parentId : parentIds) {
            try {
                // 查询条件：parentId=xxx AND isParent=true
                var filter = b.and(
                        b.eq(ParentChildChunkStrategy.META_PARENT_ID, parentId),
                        b.eq(ParentChildChunkStrategy.META_IS_PARENT, true)
                ).build();

                List<Document> found = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .filterExpression(filter)
                                .topK(1)
                                .build()
                );

                if (!found.isEmpty()) {
                    result.put(parentId, found.get(0));
                }
            } catch (Exception e) {
                log.warn("Failed to fetch parent document for parentId={}: {}", parentId, e.getMessage());
            }
        }

        return result;
    }
}
