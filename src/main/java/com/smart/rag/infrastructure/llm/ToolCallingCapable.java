package com.smart.rag.infrastructure.llm;

import java.util.List;

/**
 * 工具调用能力契约（ISP 拆分）
 * <p>
 * 从 ChatCapable 中独立出来，只有支持工具调用的 Chat 客户端才需要实现此接口。
 * AgentModeStrategy 等需要工具调用的调用方显式依赖此接口。
 * <p>
 * 获取方式：
 * <pre>
 * ChatCapable client = registry.get("qwen3-max", ChatCapable.class);
 * if (client instanceof ToolCallingCapable tc) {
 *     LlmResponse resp = tc.chatWithTools(request, tools);
 * }
 * </pre>
 */
public interface ToolCallingCapable extends ChatCapable {

    /** 带工具调用的对话（Agent 场景） */
    LlmResponse chatWithTools(ChatRequest request, List<Object> tools);
}
