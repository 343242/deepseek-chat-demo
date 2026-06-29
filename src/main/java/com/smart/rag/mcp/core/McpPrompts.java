package com.smart.rag.mcp.core;

/**
 * MCP prompts 能力（路径 C / 出口②，内核接口）。
 * <p>
 * {@code get} 委托本 server 的 {@code McpSyncClient.getPrompt(new GetPromptRequest(...))}，内核统一 authz。
 */
public interface McpPrompts {

    /**
     * 取回远端 prompt。
     *
     * @param name prompt 名
     * @param args prompt 参数
     * @param subj 调用方主体
     * @return prompt 内容
     * @throws com.smart.rag.infrastructure.exception.ClientException authz 拒绝（A 类）
     */
    McpPrompt get(String name, McpArgs args, Subject subj);
}
