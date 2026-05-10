package com.demo.chat.rag.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.*;

/**
 * 父文档后处理器
 * <p>
 * 在向量检索命中子切分后，执行以下处理：
 * <ol>
 *   <li>识别子切分（metadata 中有 parentId）</li>
 *   <li>将子切分替换为其父文档内容（提供完整上下文）</li>
 *   <li>按 parentId 去重（同一父文档的多个子切分只保留一个父文档）</li>
 *   <li>保持原始检索顺序（按首次命中的子切分排序）</li>
 * </ol>
 */
public class ParentDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ParentDocumentPostProcessor.class);

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        // LinkedHashMap 保持插入顺序
        Map<String, ParentEntry> parentMap = new LinkedHashMap<>();
        List<Document> nonChildDocs = new ArrayList<>();

        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            Object parentIdObj = metadata.get(ParentChildChunkStrategy.META_PARENT_ID);

            if (parentIdObj != null) {
                String parentId = parentIdObj.toString();
                String parentContent = (String) metadata.get(ParentChildChunkStrategy.META_PARENT_CONTENT);

                if (parentContent != null && !parentMap.containsKey(parentId)) {
                    Document parentDoc = new Document(parentContent, new HashMap<>(metadata));
                    parentDoc.getMetadata().put(ParentChildChunkStrategy.META_IS_PARENT, true);
                    parentDoc.getMetadata().remove(ParentChildChunkStrategy.META_PARENT_CONTENT);
                    parentDoc.getMetadata().remove(ParentChildChunkStrategy.META_CHILD_INDEX_IN_PARENT);

                    parentMap.put(parentId, new ParentEntry(parentDoc, doc));
                }
            } else {
                nonChildDocs.add(doc);
            }
        }

        List<Document> result = new ArrayList<>(parentMap.size() + nonChildDocs.size());
        for (ParentEntry entry : parentMap.values()) {
            result.add(entry.parentDoc);
        }
        result.addAll(nonChildDocs);

        log.debug("ParentDocumentPostProcessor: {} child docs → {} parent docs + {} non-child docs",
                documents.size(), parentMap.size(), nonChildDocs.size());

        return result;
    }

    private static class ParentEntry {
        final Document parentDoc;
        final Document firstChild;

        ParentEntry(Document parentDoc, Document firstChild) {
            this.parentDoc = parentDoc;
            this.firstChild = firstChild;
        }
    }
}
