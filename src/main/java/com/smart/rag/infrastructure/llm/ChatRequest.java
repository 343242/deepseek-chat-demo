package com.smart.rag.infrastructure.llm;

import java.util.List;
import java.util.Map;

/**
 * Chat 请求
 * <p>
 * 仅包含 Chat 场景所需的字段。Embedding 和 Rerank 各自定义独立的请求类型。
 */
public record ChatRequest(
    String input,
    String systemPrompt,
    List<MessageInformation> history,
    Double temperature,
    Integer maxTokens,
    Double topP,
    Map<String, Object> extraParams
) {
    public static ChatRequest of(String input) {
        return new ChatRequest(input, null, List.of(),
            null, null, null, Map.of());
    }

    public static ChatRequest withSystem(String systemPrompt, String input) {
        return new ChatRequest(input, systemPrompt, List.of(),
            null, null, null, Map.of());
    }

    public static Builder builder(String input) {
        return new Builder(input);
    }

    public static Builder fromDefaults(String input, ModelCandidate candidate) {
        Map<String, Object> defaults = candidate.params();
        return builder(input)
            .temperature((Double) defaults.get("temperature"))
            .maxTokens((Integer) defaults.get("maxTokens"))
            .topP((Double) defaults.get("topP"));
    }

    public static class Builder {
        private final String input;
        private String systemPrompt;
        private List<MessageInformation> history = List.of();
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private Map<String, Object> extraParams = Map.of();

        private Builder(String input) { this.input = input; }

        public Builder systemPrompt(String sp) { this.systemPrompt = sp; return this; }
        public Builder history(List<MessageInformation> h) { this.history = h; return this; }
        public Builder temperature(Double t) { this.temperature = t; return this; }
        public Builder maxTokens(Integer mt) { this.maxTokens = mt; return this; }
        public Builder topP(Double tp) { this.topP = tp; return this; }
        public Builder extraParams(Map<String, Object> ep) { this.extraParams = ep; return this; }

        public ChatRequest build() {
            return new ChatRequest(input, systemPrompt, history,
                temperature, maxTokens, topP, extraParams);
        }
    }
}
