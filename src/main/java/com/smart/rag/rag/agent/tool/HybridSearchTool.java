package com.smart.rag.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.rag.agent.service.HybridSearchService;
import com.smart.rag.rag.agent.workspace.RetrievedDocument;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.agent.dto.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索 Tool -- 向量 + BM25 + RRF 融合
 * <p>
 * 委托 HybridSearchService 执行检索，结果追加到 Workspace。
 * Phase 3 将填充完整实现逻辑（含异常捕获 + ToolResult 格式化）。
 */
@Component
public class HybridSearchTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchTool.class);

    private final HybridSearchService hybridSearchService;
    private final ObjectMapper objectMapper;

    public HybridSearchTool(HybridSearchService hybridSearchService, ObjectMapper objectMapper) {
        this.hybridSearchService = hybridSearchService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行混合检索
     *
     * @param queryText 查询文本
     * @param topK      返回数量上限（可空，使用默认值）
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    public String execute(String queryText, Integer topK, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            if (queryText == null || queryText.isBlank()) {
                return ToolResult.failure("hybridSearch",
                    "查询文本不能为空", "INVALID_INPUT", 0).toJson(objectMapper);
            }

            List<Document> docs = hybridSearchService.hybridSearch(
                queryText, workspace.getUserId(), workspace.getTeamId());

            long duration = System.currentTimeMillis() - start;
            List<RetrievedDocument> retrieved = toRetrievedDocs(docs, workspace);
            workspace.addRetrievedDocs(retrieved);

            return ToolResult.success("hybridSearch",
                "检索到 " + docs.size() + " 个相关文档片段",
                retrieved, duration).toJson(objectMapper);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Hybrid search error", e);
            return ToolResult.failure("hybridSearch",
                ToolErrorMessages.searchUnavailable("混合"),
                "INTERNAL_ERROR", duration).toJson(objectMapper);
        }
    }

    private List<RetrievedDocument> toRetrievedDocs(List<Document> docs, ToolWorkspace workspace) {
        List<RetrievedDocument> result = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            Map<String, Object> metadata = doc.getMetadata() != null
                ? new HashMap<>(doc.getMetadata()) : Map.of();
            result.add(new RetrievedDocument(
                doc.getId(),
                doc.getText(),
                doc.getScore() != null ? doc.getScore() : 0.0,
                "hybridSearch",
                -1,
                metadata
            ));
        }
        return result;
    }
}
