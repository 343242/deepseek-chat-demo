package com.demo.chat.rag.chunk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * 父文档后处理器
 * <p>
 * 在向量检索命中子切分后，执行以下处理：
 * <ol>
 *   <li>识别子切分（metadata 中有 parentId 且 isParent=false）</li>
 *   <li>通过 parentId 从 vector_store 表批量回查父文档内容</li>
 *   <li>将子切分替换为其父文档内容（提供完整上下文）</li>
 *   <li>按 parentId 去重（同一父文档的多个子切分只保留一个父文档）</li>
 *   <li>保持原始检索顺序（按首次命中的子切分排序）</li>
 * </ol>
 *
 * <p>优化：使用 JdbcTemplate 直接查询 PG 的 vector_store 表，一条 SQL 批量拉取所有父文档，
 * 替代原先逐个 parentId 调用 VectorStore.similaritySearch 的 N+1 问题。</p>
 */
public class ParentDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ParentDocumentPostProcessor.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ParentDocumentPostProcessor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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

        // 批量回查父文档（一条 SQL）
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
     * 从 vector_store 表批量回查父文档
     * <p>
     * 使用 JdbcTemplate 直接查询 PG，一条 SQL 按 parentId 批量拉取，
     * 替代原先逐个 VectorStore.similaritySearch 的 N+1 问题。
     * 查询条件：metadata->>'parentId' IN (...) AND metadata->>'isParent' = 'true'
     */
    private Map<String, Document> fetchParentDocuments(Set<String> parentIds) {
        if (parentIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Document> result = new HashMap<>();

        try {
            // 构建 IN 子句的占位符
            String placeholders = String.join(",", Collections.nCopies(parentIds.size(), "?"));
            String sql = """
                SELECT id, content, metadata
                FROM vector_store
                WHERE metadata->>'parentId' IN (%s)
                  AND metadata->>'isParent' = 'true'
                """.formatted(placeholders);

            Object[] params = parentIds.toArray();

            List<Document> docs = jdbcTemplate.query(sql,
                    (rs, rowNum) -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");

                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        return new Document(id, content, metadata);
                    },
                    params);

            for (Document doc : docs) {
                Object pid = doc.getMetadata().get(ParentChildChunkStrategy.META_PARENT_ID);
                if (pid != null) {
                    result.put(pid.toString(), doc);
                }
            }

            log.debug("Batch fetched {} parent docs for {} parentIds", docs.size(), parentIds.size());
        } catch (Exception e) {
            log.warn("Batch parent fetch failed, falling back to child chunks: {}", e.getMessage());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse metadata JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
