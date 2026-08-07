package com.smart.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.dto.ToolResult;
import com.smart.rag.rag.retrieval.RetrievedDocument;
import com.smart.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.chunk.ParentChildChunkStrategy;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 父文档查找 Tool -- 将检索到的文档片段替换为其所属的完整父文档
 * <p>
 * 复用 ParentDocumentPostProcessor 核心逻辑：
 * 1. 识别子切分（metadata 含 parentId 且 isParent=false）
 * 2. 通过 VectorStoreMapper.batchFetchParents() 批量获取父文档
 * 3. 按 parentId 去重，按子块最高分数降序排列父文档（Parent-level Rescoring）
 * 4. 替换 workspace 中的文档列表
 */
@Component
public class ParentDocLookupTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(ParentDocLookupTool.class);

    /** 分数 metadata key 的优先级链：rerankScore > rrfScore > 默认值 */
    private static final String[] SCORE_KEYS = {"rerankScore", "rrfScore"};
    private static final double DEFAULT_SCORE = 0.5;

    private final VectorStoreMapper vectorStoreMapper;
    private final ObjectMapper objectMapper;

    public ParentDocLookupTool(VectorStoreMapper vectorStoreMapper, ObjectMapper objectMapper) {
        this.vectorStoreMapper = vectorStoreMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行父文档查找
     *
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    public String execute(ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            List<RetrievedDocument> docs = workspace.getRetrievedDocs();
            if (docs.isEmpty()) {
                return ToolResult.failure("parentDocLookup",
                    "没有可查找父文档的子块。请先调用检索工具获取文档。",
                    "PRECONDITION_FAILED", System.currentTimeMillis() - start).toJson(objectMapper);
            }

            // 收集所有需要回查的 parentId
            Set<String> parentIdsToFetch = new LinkedHashSet<>();
            List<RetrievedDocument> nonChildDocs = new ArrayList<>();

            for (RetrievedDocument doc : docs) {
                Map<String, Object> metadata = doc.metadata();
                if (metadata == null) {
                    nonChildDocs.add(doc);
                    continue;
                }

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

            if (parentIdsToFetch.isEmpty()) {
                return ToolResult.failure("parentDocLookup",
                    "当前文档无父子关系，无需父文档查找。",
                    "PRECONDITION_FAILED", System.currentTimeMillis() - start).toJson(objectMapper);
            }

            // 批量回查父文档
            Map<String, Document> parentDocMap = vectorStoreMapper.batchFetchParents(parentIdsToFetch);

            // 构建结果：子切分 -> 父文档（去重，保持顺序）
            Map<String, RetrievedDocument> resolvedParents = new LinkedHashMap<>();
            for (RetrievedDocument doc : docs) {
                Map<String, Object> metadata = doc.metadata();
                if (metadata == null) continue;

                Object isParent = metadata.get(ParentChildChunkStrategy.META_IS_PARENT);
                if (Boolean.TRUE.equals(isParent)) continue;

                Object parentIdObj = metadata.get(ParentChildChunkStrategy.META_PARENT_ID);
                if (parentIdObj == null) continue;

                String parentId = parentIdObj.toString();
                if (resolvedParents.containsKey(parentId)) continue;

                Document parentDoc = parentDocMap.get(parentId);
                if (parentDoc != null) {
                    RetrievedDocument parentRd = RetrievedDocument.from(parentDoc)
                        .withSource("parentDocLookup")
                        .withScore(doc.score())
                        .withSubQueryIndex(doc.subQueryIndex());
                    parentRd.metadata().put("sourceDocId", doc.chunkId());
                    // Fix H4: parentId 必须写入 metadata，否则后续排序读不到
                    parentRd.metadata().put(ParentChildChunkStrategy.META_PARENT_ID, parentId);
                    resolvedParents.put(parentId, parentRd);
                } else {
                    log.warn("Parent document not found for parentId={}, using child chunk as fallback", parentId);
                    resolvedParents.put(parentId, doc);
                }
            }

            // Parent-level Rescoring：构建 parentId -> max(childScore) 映射
            Map<String, Double> parentScoreMap = new HashMap<>();
            for (RetrievedDocument doc : docs) {
                Map<String, Object> metadata = doc.metadata();
                if (metadata == null) continue;

                Object isParent = metadata.get(ParentChildChunkStrategy.META_IS_PARENT);
                if (Boolean.TRUE.equals(isParent)) continue;

                Object parentIdObj = metadata.get(ParentChildChunkStrategy.META_PARENT_ID);
                if (parentIdObj == null) continue;

                double score = resolveScore(doc);
                parentScoreMap.merge(parentIdObj.toString(), score, Math::max);
            }

            // 合并结果：父文档 + 非子文档
            List<RetrievedDocument> result = new ArrayList<>(resolvedParents.size() + nonChildDocs.size());

            // 对父文档按子块最高分数降序排列
            List<RetrievedDocument> parentDocs = new ArrayList<>(resolvedParents.values());
            parentDocs.sort((a, b) -> {
                // 从 metadata 中取 parentId 用于查分
                String pidA = a.metadata() != null ? getMetadataStr(a.metadata(), "parentId") : null;
                String pidB = b.metadata() != null ? getMetadataStr(b.metadata(), "parentId") : null;
                double scoreA = pidA != null ? parentScoreMap.getOrDefault(pidA, DEFAULT_SCORE) : DEFAULT_SCORE;
                double scoreB = pidB != null ? parentScoreMap.getOrDefault(pidB, DEFAULT_SCORE) : DEFAULT_SCORE;
                return Double.compare(scoreB, scoreA);
            });

            result.addAll(parentDocs);
            result.addAll(nonChildDocs);

            // 替换 workspace
            workspace.replaceRetrievedDocs(result);

            long duration = System.currentTimeMillis() - start;
            log.info("Parent doc lookup: {} docs -> {} parent docs + {} non-child docs in {}ms",
                docs.size(), resolvedParents.size(), nonChildDocs.size(), duration);

            return ToolResult.success("parentDocLookup",
                "父文档查找完成：" + docs.size() + " 个文档片段替换为 " + resolvedParents.size()
                    + " 个父文档" + (nonChildDocs.isEmpty() ? "" : "，保留 " + nonChildDocs.size() + " 个非子块文档"),
                result, duration).toJson(objectMapper);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Parent doc lookup error", e);
            return ToolResult.failure("parentDocLookup",
                ToolErrorMessages.parentDocUnavailable(),
                "DB_ERROR", duration).toJson(objectMapper);
        }
    }

    /**
     * 从文档 metadata 中提取分数，按优先级链：rerankScore > rrfScore > 默认值
     */
    private double resolveScore(RetrievedDocument doc) {
        if (doc.metadata() == null) return DEFAULT_SCORE;
        for (String key : SCORE_KEYS) {
            Object value = doc.metadata().get(key);
            if (value instanceof Number num) {
                return num.doubleValue();
            }
        }
        return doc.score() > 0 ? doc.score() : DEFAULT_SCORE;
    }

    private String getMetadataStr(Map<String, Object> metadata, String key) {
        Object val = metadata.get(key);
        return val != null ? val.toString() : null;
    }
}
