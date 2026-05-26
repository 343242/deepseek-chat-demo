package com.smart.rag.rag.agent.tool;

import com.smart.rag.rag.agent.dto.ToolResult;
import com.smart.rag.rag.agent.workspace.RetrievedDocument;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 全文检索 Tool -- PostgreSQL tsvector 关键词匹配
 * <p>
 * 委托 VectorStoreMapper.bm25Search() 执行 BM25 检索。
 * 从 workspace 获取隔离参数（userId/teamId）。
 * 结果通过 workspace.addRetrievedDocsDeduplicated() 去重追加。
 */
@Component
public class Bm25SearchTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(Bm25SearchTool.class);

    private final VectorStoreMapper vectorStoreMapper;
    private final RagRetrievalProperties properties;

    public Bm25SearchTool(VectorStoreMapper vectorStoreMapper, RagRetrievalProperties properties) {
        this.vectorStoreMapper = vectorStoreMapper;
        this.properties = properties;
    }

    /**
     * 执行 BM25 全文检索
     *
     * @param queryText 查询文本
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    public String execute(String queryText, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            if (queryText == null || queryText.isBlank()) {
                return ToolResult.failure("bm25Search",
                    "查询文本不能为空", "INVALID_INPUT", 0).toJson();
            }

            // 净化查询文本
            String sanitized = sanitizeQuery(queryText);
            if (sanitized.isBlank()) {
                return ToolResult.failure("bm25Search",
                    "查询文本经净化后为空，请提供包含有效关键词的查询。",
                    "INVALID_INPUT", 0).toJson();
            }

            // 从 workspace 获取隔离参数
            String isolationField = workspace.getTeamId() != null ? "teamId" : "userId";
            String isolationValue = workspace.getTeamId() != null
                ? String.valueOf(workspace.getTeamId())
                : String.valueOf(workspace.getUserId());

            List<Document> docs = vectorStoreMapper.bm25Search(
                properties.ftsConfig(),
                sanitized,
                isolationField,
                isolationValue,
                properties.bm25TopK()
            );

            // 转为 RetrievedDocument
            List<RetrievedDocument> retrieved = new ArrayList<>(docs.size());
            for (Document doc : docs) {
                Map<String, Object> metadata = doc.getMetadata() != null
                    ? new HashMap<>(doc.getMetadata()) : new HashMap<>();
                metadata.put("retrievalSource", "bm25");
                retrieved.add(new RetrievedDocument(
                    doc.getId(),
                    doc.getText(),
                    0.0, // BM25 分数不直接可用，由 RRF 融合时计算
                    "bm25Search",
                    -1,
                    metadata
                ));
            }

            // P1 去重追加
            workspace.addRetrievedDocsDeduplicated(retrieved);

            long duration = System.currentTimeMillis() - start;
            log.debug("BM25 search: query='{}', {} results in {}ms", sanitized, docs.size(), duration);

            return ToolResult.success("bm25Search",
                "BM25 检索到 " + docs.size() + " 个相关文档片段",
                retrieved, duration).toJson();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("BM25 search error: {}", e.getMessage(), e);
            return ToolResult.failure("bm25Search",
                "BM25 检索发生错误：" + e.getMessage() + "。建议尝试向量检索（vectorSearch）或混合检索（hybridSearch）。",
                "DB_ERROR", duration).toJson();
        }
    }

    /**
     * 净化查询文本：只去掉 PostgreSQL tsquery 运算符，保留完整中文文本交给 pg_jieba 分词。
     */
    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) return "";
        return query
            .replace('“', ' ').replace('”', ' ')
            .replace('‘', ' ').replace('’', ' ')
            .replace('«', ' ').replace('»', ' ')
            .replaceAll("[&|!()\\[\\]{}:*\\\\\"']", " ")
            .trim();
    }
}
