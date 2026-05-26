package com.smart.rag.rag.agent.tool;

/**
 * Tool 错误文案工厂 — 所有 Tool failure 必须使用此类生成用户/模型可见文案。
 * 禁止将 e.getMessage() 拼入 ToolResult。
 */
public final class ToolErrorMessages {

    private ToolErrorMessages() {}

    public static String searchUnavailable(String searchType) {
        return searchType + "检索服务暂时不可用，请改用已有上下文或尝试其他检索方式。";
    }

    public static String rerankUnavailable() {
        return "精排服务暂时不可用，建议直接使用原始检索结果生成回答。";
    }

    public static String rewriteFailed() {
        return "查询改写暂时不可用，建议使用原始查询直接检索。";
    }

    public static String docDetailUnavailable() {
        return "文档详情获取暂时不可用，请使用已有上下文生成回答。";
    }

    public static String parentDocUnavailable() {
        return "父文档查找暂时不可用，建议使用原始检索结果生成回答。";
    }

    public static String eventLookupUnavailable() {
        return "历史事件查找暂时不可用，请基于当前上下文生成回答。";
    }

    public static String knowledgeBaseUnavailable() {
        return "知识库信息查询暂时不可用，请基于已有上下文生成回答。";
    }
}
