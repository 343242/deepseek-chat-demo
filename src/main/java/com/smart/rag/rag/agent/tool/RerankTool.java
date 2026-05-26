package com.smart.rag.rag.agent.tool;

import com.smart.rag.rag.agent.dto.ToolResult;
import com.smart.rag.rag.agent.workspace.RetrievedDocument;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.rag.retrieval.BailianRerankPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rerank Tool -- 对已检索文档进行语义精排
 * <p>
 * 委托 BailianRerankPostProcessor 核心逻辑（百炼 qwen3-rerank 模型）。
 * 从 workspace 获取已检索文档，调用 Rerank API 后替换 workspace 中的文档列表。
 */
@Component
public class RerankTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(RerankTool.class);

    private final RagRetrievalProperties properties;

    public RerankTool(RagRetrievalProperties properties) {
        this.properties = properties;
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
                    "PRECONDITION_FAILED", System.currentTimeMillis() - start).toString();
            }

            if (queryText == null || queryText.isBlank()) {
                return ToolResult.failure("rerank",
                    "查询文本不能为空，请提供用于精排的查询文本。",
                    "INVALID_INPUT", 0).toString();
            }

            // 检查 Rerank 是否启用且 API Key 已配置
            if (!properties.rerankEnabled() || properties.rerankApiKey() == null
                || properties.rerankApiKey().isBlank()) {
                return ToolResult.failure("rerank",
                    "Rerank 未启用或 API Key 未配置。跳过精排步骤。",
                    "PRECONDITION_FAILED", System.currentTimeMillis() - start).toString();
            }

            // 将 RetrievedDocument 转为 Spring AI Document
            List<Document> springDocs = new ArrayList<>(docs.size());
            for (RetrievedDocument rd : docs) {
                Map<String, Object> metadata = rd.metadata() != null
                    ? new HashMap<>(rd.metadata()) : new HashMap<>();
                springDocs.add(new Document(rd.docId(), rd.content(), metadata));
            }

            // 创建 BailianRerankPostProcessor 实例执行精排
            BailianRerankPostProcessor reranker = new BailianRerankPostProcessor(
                properties.rerankBaseUrl(),
                properties.rerankApiKey(),
                properties.rerankModel(),
                properties.rerankTopN()
            );

            try {
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
                    rerankedDocs, duration).toString();

            } finally {
                reranker.shutdown();
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Rerank error: {}", e.getMessage(), e);
            return ToolResult.failure("rerank",
                "精排发生错误：" + e.getMessage() + "。建议直接使用原始检索结果生成回答。",
                "API_ERROR", duration).toString();
        }
    }
}
