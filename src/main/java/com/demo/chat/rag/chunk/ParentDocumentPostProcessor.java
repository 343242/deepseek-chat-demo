package com.demo.chat.rag.chunk;

import com.demo.chat.rag.mapper.VectorStoreMapper;
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
 *   <li>识别子切分（metadata 中有 parentId 且 isParent=false）</li>
 *   <li>通过 parentId 从 vector_store 表批量回查父文档内容</li>
 *   <li>将子切分替换为其父文档内容（提供完整上下文）</li>
 *   <li>按 parentId 去重（同一父文档的多个子切分只保留一个父文档）</li>
 *   <li>按子块的最高分数降序重排父文档（Parent-level Rescoring）</li>
 * </ol>
 * <p>
 * Parent-level Rescoring 基于 H-RAG (arXiv:2605.00631) 的发现：
 * 子→父替换后按 max(childScore) 重排父文档是所有配置中收益最大的因素（+0.0197 nDCG@5）。
 * <p>
 * SQL 委托给 {@link VectorStoreMapper}，批量一条 SQL 拉取所有父文档。
 */
public class ParentDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ParentDocumentPostProcessor.class);

    /** 分数 metadata key 的优先级链：rerankScore > rrfScore > 默认值 */
    private static final String[] SCORE_KEYS = {"rerankScore", "rrfScore"};
    private static final double DEFAULT_SCORE = 0.5;

    private final VectorStoreMapper vectorStoreMapper;

    public ParentDocumentPostProcessor(VectorStoreMapper vectorStoreMapper) {
        this.vectorStoreMapper = vectorStoreMapper;
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

        // 批量回查父文档（委托 Mapper）
        Map<String, Document> parentDocMap = vectorStoreMapper.batchFetchParents(parentIdsToFetch);

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

        // 构建 parentId → max(childScore) 映射（Parent-level Rescoring）
        Map<String, Double> parentScoreMap = new HashMap<>();
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            Object isParent = metadata.get(ParentChildChunkStrategy.META_IS_PARENT);
            if (Boolean.TRUE.equals(isParent)) continue;

            Object parentIdObj = metadata.get(ParentChildChunkStrategy.META_PARENT_ID);
            if (parentIdObj == null) continue;

            double score = resolveScore(doc);
            parentScoreMap.merge(parentIdObj.toString(), score, Math::max);
        }

        List<Document> result = new ArrayList<>(resolvedParents.size() + nonChildDocs.size());
        result.addAll(resolvedParents.values());
        result.addAll(nonChildDocs);

        // Parent-level Rescoring：按子块最高分数降序排列父文档
        // 只对父文档部分排序，non-child 保持原序
        // 注意：parentScoreMap 的 key 是 metadata 中的 parentId，不是 doc.getId()
        if (!parentScoreMap.isEmpty() && result.size() > 1) {
            int parentCount = resolvedParents.size();
            List<Document> parentDocs = result.subList(0, parentCount);
            parentDocs.sort(Comparator.comparingDouble(
                    (Document doc) -> {
                        Object pid = doc.getMetadata().get(ParentChildChunkStrategy.META_PARENT_ID);
                        return pid != null
                                ? parentScoreMap.getOrDefault(pid.toString(), DEFAULT_SCORE)
                                : DEFAULT_SCORE;
                    })
                    .reversed());
        }

        log.debug("ParentDocumentPostProcessor: {} docs → {} parent docs + {} non-child docs",
                documents.size(), resolvedParents.size(), nonChildDocs.size());

        return result;
    }

    /**
     * 从文档 metadata 中提取分数，按优先级链：rerankScore > rrfScore > 默认值
     *
     * @param doc 文档
     * @return 分数值
     */
    static double resolveScore(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        for (String key : SCORE_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof Number num) {
                return num.doubleValue();
            }
        }
        return DEFAULT_SCORE;
    }
}
