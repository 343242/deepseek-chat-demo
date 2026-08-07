package com.smart.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.rag.retrieval.HybridSearchService;
import com.smart.rag.rag.retrieval.RetrievedDocument;
import com.smart.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.agent.dto.ToolResult;
import com.smart.rag.infrastructure.trace.TracedStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
    @TracedStep("HYBRID_SEARCH")
    public String execute(String queryText, Integer topK, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            if (queryText == null || queryText.isBlank()) {
                return ToolResult.failure("hybridSearch",
                    "查询文本不能为空", "INVALID_INPUT", 0).toJson(objectMapper);
            }

            // 注入 sessionId 到 MDC，供下游 PATH_RECALL trace 记录关联（与 Chat 路径 AbstractModeStrategy 同一 key）
            if (workspace.getSessionId() != null) {
                MDC.put("ragSessionId", workspace.getSessionId());
            }
            MDC.put("ragMode", "AGENT");

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
            result.add(RetrievedDocument.from(doc).withSource("hybridSearch"));
        }
        return result;
    }
}
