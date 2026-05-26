package com.smart.rag.rag.agent.tool;

/**
 * RAG Tool 标记接口
 * <p>
 * 用于 AgentToolCallbackFactory 区分 RAG Tool 和通用 Tool。
 * 实现 RagTool 的 @Component Bean 会被自动归类为 RAG Tool。
 */
public interface RagTool {
}
