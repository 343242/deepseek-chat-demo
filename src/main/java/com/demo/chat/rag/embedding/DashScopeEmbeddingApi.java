package com.demo.chat.rag.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DashScope OpenAI 兼容 API 的请求/响应 DTO
 * <p>
 * 封装与 DashScope /v1/embeddings 端点通信的数据结构。
 * 不依赖任何外部 SDK，纯 POJO。
 * </p>
 */
public final class DashScopeEmbeddingApi {

    private DashScopeEmbeddingApi() {}

    // === Request ===

    public static class Request {
        private String model;
        private List<String> input;
        private Integer dimensions;

        public Request() {}

        public Request(String model, List<String> input, Integer dimensions) {
            this.model = model;
            this.input = input;
            this.dimensions = dimensions;
        }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public List<String> getInput() { return input; }
        public void setInput(List<String> input) { this.input = input; }
        public Integer getDimensions() { return dimensions; }
        public void setDimensions(Integer dimensions) { this.dimensions = dimensions; }
    }

    // === Response ===

    public static class Response {
        private String object;
        private List<EmbeddingData> data;
        private String model;
        private Usage usage;

        public String getObject() { return object; }
        public void setObject(String object) { this.object = object; }
        public List<EmbeddingData> getData() { return data; }
        public void setData(List<EmbeddingData> data) { this.data = data; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Usage getUsage() { return usage; }
        public void setUsage(Usage usage) { this.usage = usage; }
    }

    public static class EmbeddingData {
        private String object;
        private List<Double> embedding;
        private int index;

        public String getObject() { return object; }
        public void setObject(String object) { this.object = object; }
        public List<Double> getEmbedding() { return embedding; }
        public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
    }

    public static class Usage {
        @JsonProperty("prompt_tokens")
        private long promptTokens;
        @JsonProperty("total_tokens")
        private long totalTokens;

        public long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
        public long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    }

    // === Error Response ===

    public static class ErrorResponse {
        private ErrorDetail error;

        public ErrorDetail getError() { return error; }
        public void setError(ErrorDetail error) { this.error = error; }
    }

    public static class ErrorDetail {
        private String message;
        private String type;
        private String code;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }
}
