package com.smart.rag.agent.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.workspace.RetrievedDocument;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 调用统一结果 record
 * <p>
 * 所有 RAG Tool 的返回值统一包装为此格式，供 LLM 阅读。
 * Tool 永远不抛异常到 ToolCallAdvisor，所有异常捕获后转为 failure。
 *
 * @param success       调用是否成功
 * @param action        Tool 名称
 * @param summary       结果摘要（供 LLM 阅读）
 * @param errorMessage  失败信息（引导 LLM 换策略）
 * @param errorCategory 错误分类：API_ERROR / DB_ERROR / INVALID_INPUT / INTERNAL_ERROR
 * @param documents     检索到的文档（失败时为 null）
 * @param durationMs    耗时（ms）
 */
public record ToolResult(
    boolean success,
    String action,
    String summary,
    @Nullable String errorMessage,
    @Nullable String errorCategory,
    @Nullable List<RetrievedDocument> documents,
    long durationMs
) {

    private static final Logger log = LoggerFactory.getLogger(ToolResult.class);
    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    /**
     * 创建成功结果
     */
    public static ToolResult success(String action, String summary,
                                     @Nullable List<RetrievedDocument> docs, long durationMs) {
        return new ToolResult(true, action, summary, null, null, docs, durationMs);
    }

    /**
     * 创建失败结果
     */
    public static ToolResult failure(String action, String errorMessage,
                                     String errorCategory, long durationMs) {
        return new ToolResult(false, action, null, errorMessage, errorCategory, null, durationMs);
    }

    /**
     * 将 ToolResult 序列化为 JSON 字符串，供 LLM 解析。
     * <p>
     * Java record 默认 toString() 产出 {@code ToolResult[success=true, ...]} 格式，
     * LLM 无法解析。此方法返回标准 JSON。
     * <p>
     * 检索到的文档（documents）只保留摘要字段（docId、score、content 截断），
     * 避免 JSON 过长。
     *
     * @deprecated 降级用，调用方应使用 {@link #toJson(ObjectMapper)}
     */
    @Deprecated
    public String toJson() {
        return toJson(FALLBACK_MAPPER);
    }

    /**
     * 将 ToolResult 序列化为 JSON 字符串，供 LLM 解析。
     * <p>
     * 使用外部注入的共享 ObjectMapper，与同模块其他组件保持一致。
     *
     * @param objectMapper 外部注入的共享 ObjectMapper
     */
    public String toJson(ObjectMapper objectMapper) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("action", action);
        if (summary != null) {
            map.put("summary", summary);
        }
        if (errorMessage != null) {
            map.put("errorMessage", errorMessage);
        }
        if (errorCategory != null) {
            map.put("errorCategory", errorCategory);
        }
        if (documents != null && !documents.isEmpty()) {
            List<Map<String, Object>> docSummaries = documents.stream().map(doc -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("docId", doc.docId());
                d.put("score", doc.score());
                // 截断内容，避免 JSON 过长
                String content = doc.content();
                if (content != null && content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                d.put("content", content);
                d.put("source", doc.source());
                return d;
            }).toList();
            map.put("documentCount", docSummaries.size());
            map.put("documents", docSummaries);
        }
        map.put("durationMs", durationMs);
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ToolResult to JSON", e);
            // 降级：手动拼装最简 JSON（action 做 JSON 转义防注入）
            String escapedAction = action == null ? ""
                : action.replace("\\", "\\\\").replace("\"", "\\\"");
            return "{\"success\":" + success + ",\"action\":\"" + escapedAction + "\"}";
        }
    }
}
