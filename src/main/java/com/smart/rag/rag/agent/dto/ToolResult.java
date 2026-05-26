package com.smart.rag.rag.agent.dto;

import com.smart.rag.rag.agent.workspace.RetrievedDocument;
import org.jspecify.annotations.Nullable;

import java.util.List;

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
}
