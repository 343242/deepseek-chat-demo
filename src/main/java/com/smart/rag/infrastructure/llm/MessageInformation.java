package com.smart.rag.infrastructure.llm;

import java.util.Map;
import java.util.Objects;

/**
 * 对话消息（供应商无关的 SPI 层消息载体）
 * <p>
 * <b>为什么用 class 而非 record</b>：{@code metadata} 不参与 equals/hashCode——
 * 它是附加调试信息（如 tool_calls 列表），不应影响消息的身份判定（如对话历史去重）。
 * <p>
 * <b>为什么命名为 MessageInformation 而非 Message</b>：项目中已存在多个同名类型：
 * <ul>
 *   <li>{@code conversation.entity.Message} — DB 持久化实体</li>
 *   <li>{@code org.springframework.ai.chat.messages.Message} — Spring AI 框架类型</li>
 * </ul>
 * 命名为 {@code MessageInformation} 避免全限定名冲突和 import 歧义。
 */
public final class MessageInformation {

    private final String role;
    private final String content;
    private final String toolCallId;
    private final Map<String, Object> metadata;

    private MessageInformation(String role, String content, String toolCallId, Map<String, Object> metadata) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.metadata = metadata != null ? metadata : Map.of();
    }

    public static MessageInformation user(String content) {
        return new MessageInformation("user", content, null, Map.of());
    }

    public static MessageInformation assistant(String content) {
        return new MessageInformation("assistant", content, null, Map.of());
    }

    public static MessageInformation system(String content) {
        return new MessageInformation("system", content, null, Map.of());
    }

    public static MessageInformation assistant(String content, Map<String, Object> metadata) {
        return new MessageInformation("assistant", content, null, metadata);
    }

    public static MessageInformation tool(String toolCallId, String content) {
        return new MessageInformation("tool", content, toolCallId, Map.of());
    }

    public static MessageInformation of(String role, String content) {
        return new MessageInformation(role, content, null, Map.of());
    }

    public String role() { return role; }
    public String content() { return content; }
    public String toolCallId() { return toolCallId; }
    public Map<String, Object> metadata() { return metadata; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageInformation other)) return false;
        return Objects.equals(role, other.role)
            && Objects.equals(content, other.content)
            && Objects.equals(toolCallId, other.toolCallId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content, toolCallId);
    }

    @Override
    public String toString() {
        return "MessageInformation{role='" + role + "', content='" +
            (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) +
            "', toolCallId='" + toolCallId + "'}";
    }
}
