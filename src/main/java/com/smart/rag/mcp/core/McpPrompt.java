package com.smart.rag.mcp.core;

import java.util.List;

/**
 * MCP prompt 取回结果（领域模型，路径 C / 出口②）。
 * <p>
 * runtime 经 {@code McpSyncClient.getPrompt(new GetPromptRequest(...))} 取回后净化。
 * Phase 1 模型就位；业务消费属 Phase 3。
 *
 * @param name        prompt 名
 * @param description prompt 描述；可空
 * @param messages    prompt 消息列表（不可变副本）
 */
public record McpPrompt(String name, String description, List<PromptMessage> messages) {

    public McpPrompt {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** 单条 prompt 消息（角色 + 文本内容）。 */
    public record PromptMessage(String role, String content) {
    }
}
