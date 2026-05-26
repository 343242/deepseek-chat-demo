package com.smart.rag.rag.agent.tool;

import com.smart.rag.rag.agent.dto.ToolResult;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档详情 Tool -- 按需获取文档片段（P0 优化，ts_headline 高亮）
 * <p>
 * 使用 VectorStoreMapper 的 ts_headline 查询获取高亮片段。
 * 不写 workspace，直接返回片段内容供 LLM 阅读。
 */
@Component
public class DocDetailTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(DocDetailTool.class);

    private final VectorStoreMapper vectorStoreMapper;
    private final RagRetrievalProperties properties;

    public DocDetailTool(VectorStoreMapper vectorStoreMapper, RagRetrievalProperties properties) {
        this.vectorStoreMapper = vectorStoreMapper;
        this.properties = properties;
    }

    /**
     * 获取文档详情
     *
     * @param docIds    文档 ID 列表（逗号分隔）
     * @param queryText 查询文本（用于 ts_headline 高亮）
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    public String execute(String docIds, String queryText, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            if (docIds == null || docIds.isBlank()) {
                return ToolResult.failure("docDetail",
                    "文档 ID 不能为空", "INVALID_INPUT", 0).toJson();
            }

            // 解析逗号分隔的文档 ID
            List<String> idList = Arrays.stream(docIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

            if (idList.isEmpty()) {
                return ToolResult.failure("docDetail",
                    "解析后无有效文档 ID", "INVALID_INPUT", 0).toJson();
            }

            // 限制查询数量防止过载
            if (idList.size() > 20) {
                idList = idList.subList(0, 20);
            }

            String query = (queryText != null && !queryText.isBlank()) ? queryText : "";
            String ftsConfig = properties.ftsConfig();

            // 使用 ts_headline 获取高亮片段
            Map<String, String> highlights = vectorStoreMapper.fetchDocHighlights(
                idList, query, ftsConfig);

            if (highlights.isEmpty()) {
                long duration = System.currentTimeMillis() - start;
                return ToolResult.failure("docDetail",
                    "未找到指定 ID 的文档片段。请检查文档 ID 是否正确。",
                    "DB_ERROR", duration).toJson();
            }

            // 构建摘要文本
            StringBuilder summary = new StringBuilder();
            summary.append("获取到 ").append(highlights.size()).append(" 个文档片段：\n");
            for (Map.Entry<String, String> entry : highlights.entrySet()) {
                summary.append("- [").append(entry.getKey()).append("]: ");
                String content = entry.getValue();
                // 截断过长内容
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                summary.append(content).append("\n");
            }

            long duration = System.currentTimeMillis() - start;
            log.debug("Doc detail: fetched {} highlights from {} ids in {}ms",
                highlights.size(), idList.size(), duration);

            return ToolResult.success("docDetail",
                summary.toString(), null, duration).toJson();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Doc detail error: {}", e.getMessage(), e);
            return ToolResult.failure("docDetail",
                "文档详情获取发生错误：" + e.getMessage(),
                "DB_ERROR", duration).toJson();
        }
    }
}
