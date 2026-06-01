package com.smart.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.dto.ToolResult;
import com.smart.rag.infrastructure.agent.workspace.RetrievedDocument;
import com.smart.rag.infrastructure.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.retrieval.BailianRerankPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rerank Tool -- 对已检索文档进行语义精排
 * <p>
 * 委托 {@link BailianRerankPostProcessor} 单例 Bean 执行精排（百炼 qwen3-rerank 模型）。
 * 从 workspace 获取已检索文档，调用 Rerank API 后替换 workspace 中的文档列表。
 */
@Component
public class RerankTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(RerankTool.class);

    private final BailianRerankPostProcessor reranker;
    private final ObjectMapper objectMapper;

    /**
     * 构造注入 Rerank 单例 Bean。
     * <p>
     * 使用 required=false 因为 Bean 仅在 rerank-enabled=true 时存在。
     * {@link #execute} 内部会检查 reranker 是否可用。
     */
    public RerankTool(@Autowired(required = false) BailianRerankPostProcessor reranker,
                      ObjectMapper objectMapper) {
        this.reranker = reranker;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 Rerank
     *
     * @param queryText 用于精排的查询文本
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    public String execute(String queryText, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            List<RetrievedDocument> docs = workspace.getRetrievedDocs();
            if (docs.isEmpty()) {
                return ToolResult.failure("rerank",
                    "没有可精排的文档。请先调用检索工具（hybridSearch 或 vectorSearch）获取文档。",
                    "PRECONDITION_FAILED", System.currentTimeMillis() - start).toJson(objectMapper);
            }

            if (queryText == null || queryText.isBlank()) {
                return ToolResult.failure("rerank",
                    "查询文本不能为空，请提供用于精排的查询文本。",
                    "INVALID_INPUT", 0).toJson(objectMapper);
            }

            // Rerank Bean 未注入说明 rerank 未启用
            if (reranker == null) {
                return ToolResult.failure("rerank",
                    "Rerank 未启用或 API Key 未配置。跳过精排步骤。",
                    "PRECONDITION_FAILED", System.currentTimeMillis() - start).toJson(objectMapper);
            }

            // 将 RetrievedDocument 转为 Spring AI Document
            List<Document> springDocs = new ArrayList<>(docs.size());
            for (RetrievedDocument rd : docs) {
                Map<String, Object> metadata = rd.metadata() != null
                    ? new HashMap<>(rd.metadata()) : new HashMap<>();
                springDocs.add(new Document(rd.docId(), rd.content(), metadata));
            }

            // 使用注入的单例 Bean 执行精排（无需 try/finally shutdown，生命周期由 Spring 管理）
            List<Document> reranked = reranker.process(new Query(queryText), springDocs);

            // 转回 RetrievedDocument 并替换 workspace
            List<RetrievedDocument> rerankedDocs = new ArrayList<>(reranked.size());
            for (Document doc : reranked) {
                Map<String, Object> metadata = doc.getMetadata() != null
                    ? new HashMap<>(doc.getMetadata()) : Map.of();
                // 保留 rerankScore
                double score = metadata.containsKey("rerankScore")
                    ? ((Number) metadata.get("rerankScore")).doubleValue()
                    : 0.0;
                rerankedDocs.add(new RetrievedDocument(
                    doc.getId(),
                    doc.getText(),
                    score,
                    "rerank",
                    -1,
                    metadata
                ));
            }
            workspace.replaceRetrievedDocs(rerankedDocs);

            long duration = System.currentTimeMillis() - start;
            log.debug("Rerank completed: {} docs -> {} docs in {}ms",
                docs.size(), rerankedDocs.size(), duration);

            return ToolResult.success("rerank",
                "精排完成：从 " + docs.size() + " 个文档中精选出 " + rerankedDocs.size() + " 个高相关文档",
                rerankedDocs, duration).toJson(objectMapper);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Rerank error", e);
            return ToolResult.failure("rerank",
                ToolErrorMessages.rerankUnavailable(),
                "API_ERROR", duration).toJson(objectMapper);
        }
    }
}
