package com.smart.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.rag.retrieval.RetrievedDocument;
import com.smart.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.agent.dto.ToolResult;
import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.infrastructure.trace.TracedStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量检索 Tool -- 纯向量语义检索
 * <p>
 * Phase 3 将填充完整实现逻辑（含异常捕获 + ToolResult 格式化）。
 */
@Component
public class VectorSearchTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchTool.class);

    private final VectorStore vectorStore;
    private final RagRetrievalProperties properties;
    private final ObjectMapper objectMapper;

    public VectorSearchTool(VectorStore vectorStore, RagRetrievalProperties properties,
                            ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行向量检索
     *
     * @param queryText 查询文本
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    @TracedStep("VECTOR_SEARCH")
    public String execute(String queryText, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            if (queryText == null || queryText.isBlank()) {
                return ToolResult.failure("vectorSearch",
                    "查询文本不能为空", "INVALID_INPUT", 0).toJson(objectMapper);
            }

            FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
            var filter = workspace.getTeamId() != null
                    ? filterBuilder.eq("teamId", String.valueOf(workspace.getTeamId())).build()
                    : filterBuilder.eq("userId", String.valueOf(workspace.getUserId())).build();

            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(queryText)
                            .topK(properties.vectorTopK())
                            .similarityThreshold(properties.similarityThreshold())
                            .filterExpression(filter)
                            .build()
            );

            long duration = System.currentTimeMillis() - start;

            List<RetrievedDocument> retrieved = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                retrieved.add(RetrievedDocument.from(doc).withSource("vectorSearch"));
            }
            workspace.addRetrievedDocs(retrieved);

            return ToolResult.success("vectorSearch",
                "检索到 " + docs.size() + " 个相关文档片段",
                retrieved, duration).toJson(objectMapper);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Vector search error", e);
            return ToolResult.failure("vectorSearch",
                ToolErrorMessages.searchUnavailable("向量"),
                "INTERNAL_ERROR", duration).toJson(objectMapper);
        }
    }
}
