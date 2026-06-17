package com.smart.rag.chat.dto;

import java.util.List;

/**
 * XML 系统提示词模板
 * <p>
 * 从 classpath:static/prompt/*.xml 解析而来，保留原始 XML 内容
 * 作为发送给大模型的 system prompt。
 * <p>
 * 结构化字段（role/rules/constraints/capabilities）用于管理界面展示和查询，
 * 原始 XML 由大模型直接读取，利用标签语义获得更好的指令遵循。
 */
public record PromptTemplate(
    String model,
    String rawXml,
    String role,
    List<String> rules,
    List<String> constraints,
    List<String> capabilities
) {

    /**
     * 获取发送给大模型的 system prompt（XML 原文）
     */
    public String toSystemPrompt() {
        return rawXml;
    }
}
