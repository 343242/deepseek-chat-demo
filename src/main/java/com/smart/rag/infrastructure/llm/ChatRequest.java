package com.smart.rag.infrastructure.llm;

import java.util.List;
import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

import java.util.Map;

/**
 * Chat 请求。新增 {@link ChatTool} tools 字段（Fix B-i），由 ChatModelAdapter
 * 从 Spring AI ToolCallingChatOptions 提取后透传给厂商。
 * 新增 {@link ThinkingConfig} thinking 字段：每请求覆盖候选 {@code params.thinking}
 * 默认的思考程度；为 null 时回落候选默认，两者皆无时不注入。
 */
public record ChatRequest(
    String input,
    String systemPrompt,
    List<MessageInformation> history,
    Double temperature,
    Integer maxTokens,
    Double topP,
    Map<String, Object> extraParams,
    List<ChatTool> tools,
    ThinkingConfig thinking
) {
    public ChatRequest {
        if (input == null) {
            throw new ClientException(ClientErrorCode.BAD_REQUEST, "ChatRequest.input 不能为 null");
        }
        history = history != null ? List.copyOf(history) : List.of();
        extraParams = extraParams != null ? Map.copyOf(extraParams) : Map.of();
        tools = tools != null ? List.copyOf(tools) : List.of();
    }

    public static ChatRequest of(String input) {
        return new ChatRequest(input, null, List.of(),
            null, null, null, Map.of(), List.of(), null);
    }

    public static ChatRequest withSystem(String systemPrompt, String input) {
        return new ChatRequest(input, systemPrompt, List.of(),
            null, null, null, Map.of(), List.of(), null);
    }

    public static Builder builder(String input) {
        return new Builder(input);
    }

    public static Builder fromDefaults(String input, ModelCandidate candidate) {
        Map<String, Object> defaults = candidate.params();
        return builder(input)
            .temperature(toDouble(defaults, "temperature"))
            .maxTokens(toInt(defaults, "maxTokens"))
            .topP(toDouble(defaults, "topP"));
    }

    private static Double toDouble(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer toInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    public static class Builder {
        private final String input;
        private String systemPrompt;
        private List<MessageInformation> history = List.of();
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private Map<String, Object> extraParams = Map.of();
        private List<ChatTool> tools = List.of();
        private ThinkingConfig thinking;

        private Builder(String input) { this.input = input; }

        public Builder systemPrompt(String sp) { this.systemPrompt = sp; return this; }
        public Builder history(List<MessageInformation> h) { this.history = h != null ? List.copyOf(h) : List.of(); return this; }
        public Builder temperature(Double t) { this.temperature = t; return this; }
        public Builder maxTokens(Integer mt) { this.maxTokens = mt; return this; }
        public Builder topP(Double tp) { this.topP = tp; return this; }
        public Builder extraParams(Map<String, Object> ep) { this.extraParams = ep != null ? Map.copyOf(ep) : Map.of(); return this; }
        public Builder tools(List<ChatTool> t) { this.tools = t != null ? List.copyOf(t) : List.of(); return this; }
        public Builder thinking(ThinkingConfig tc) { this.thinking = tc; return this; }

        public ChatRequest build() {
            if (input == null || input.isBlank()) throw new ClientException(ClientErrorCode.BAD_REQUEST, "ChatRequest.input 不能为空");
            return new ChatRequest(input, systemPrompt, history,
                temperature, maxTokens, topP, extraParams, tools, thinking);
        }
    }
}
