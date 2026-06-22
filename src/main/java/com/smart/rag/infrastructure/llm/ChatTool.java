package com.smart.rag.infrastructure.llm;

/**
 * 厂商无关工具定义：把 Spring AI ToolCallback 透传到厂商 Chat 请求（Fix B-i）。
 */
public record ChatTool(String name, String description, String inputSchemaJson) {
}
