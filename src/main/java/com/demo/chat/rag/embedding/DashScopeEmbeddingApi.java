package com.demo.chat.rag.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * DashScope 原生 API 的请求/响应 DTO。
 * <p>
 * 封装与 DashScope /api/v1/services/embeddings/text-embedding/text-embedding 端点通信的数据结构。
 * 原生 API 支持 text_type、instruct 等高级参数，OpenAI 兼容接口不支持。
 * </p>
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api">百炼向量模型同步接口文档</a>
 */
public final class DashScopeEmbeddingApi {

    private DashScopeEmbeddingApi() {}

    // === Request ===

    /**
     * DashScope 原生 Embedding 请求体。
     * <pre>
     * {
     *   "model": "text-embedding-v4",
     *   "input": { "texts": ["text1", "text2"] },
     *   "parameters": {
     *     "dimension": 1024,
     *     "text_type": "query",
     *     "output_type": "dense"
     *   }
     * }</pre>
     */
    public static class Request {
        private String model;
        private InputWrapper input;
        private Parameters parameters;

        public Request() {}

        /**
         * 构建完整的 DashScope 原生请求。
         *
         * @param model      模型名称
         * @param texts      待向量化的文本列表
         * @param dimensions 向量维度
         * @param textType   文本类型（null 表示不传）
         * @param instruct   任务指令（null 或空表示不传）
         */
        public Request(String model, List<String> texts, int dimensions,
                       TextType textType, String instruct) {
            this.model = model;
            this.input = new InputWrapper(texts);
            this.parameters = new Parameters(dimensions, textType, instruct);
        }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public InputWrapper getInput() { return input; }
        public void setInput(InputWrapper input) { this.input = input; }
        public Parameters getParameters() { return parameters; }
        public void setParameters(Parameters parameters) { this.parameters = parameters; }
    }

    /** input 包装层：{ "texts": [...] } */
    public static class InputWrapper {
        private List<String> texts;

        public InputWrapper() {}

        public InputWrapper(List<String> texts) {
            this.texts = texts;
        }

        public List<String> getTexts() { return texts; }
        public void setTexts(List<String> texts) { this.texts = texts; }
    }

    /** parameters 层：dimension、text_type、output_type、instruct */
    public static class Parameters {
        private int dimension;
        private String textType;
        private String outputType = "dense";

        @JsonProperty("instruct")
        private String instruct;

        public Parameters() {}

        public Parameters(int dimension, TextType textType, String instruct) {
            this.dimension = dimension;
            if (textType != null && textType != TextType.DISABLED) {
                this.textType = textType.getValue();
            }
            if (instruct != null && !instruct.isBlank()) {
                this.instruct = instruct;
            }
        }

        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
        public String getTextType() { return textType; }
        public void setTextType(String textType) { this.textType = textType; }
        public String getOutputType() { return outputType; }
        public void setOutputType(String outputType) { this.outputType = outputType; }
        public String getInstruct() { return instruct; }
        public void setInstruct(String instruct) { this.instruct = instruct; }
    }

    // === Response ===

    /**
     * DashScope 原生 Embedding 响应体。
     * <pre>
     * {
     *   "output": { "embeddings": [{ "embedding": [...], "text_index": 0 }] },
     *   "usage": { "total_tokens": 100 },
     *   "request_id": "xxx"
     * }</pre>
     */
    public static class Response {
        private Output output;
        private Usage usage;

        @JsonProperty("request_id")
        private String requestId;

        public Output getOutput() { return output; }
        public void setOutput(Output output) { this.output = output; }
        public Usage getUsage() { return usage; }
        public void setUsage(Usage usage) { this.usage = usage; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
    }

    public static class Output {
        private List<EmbeddingData> embeddings;

        public List<EmbeddingData> getEmbeddings() { return embeddings; }
        public void setEmbeddings(List<EmbeddingData> embeddings) { this.embeddings = embeddings; }
    }

    public static class EmbeddingData {
        private List<Double> embedding;

        @JsonProperty("text_index")
        private int textIndex;

        public List<Double> getEmbedding() { return embedding; }
        public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
        public int getTextIndex() { return textIndex; }
        public void setTextIndex(int textIndex) { this.textIndex = textIndex; }
    }

    public static class Usage {

        @JsonProperty("total_tokens")
        private long totalTokens;

        public long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    }

    // === Error Response ===

    public static class ErrorResponse {
        private String code;
        private String message;

        @JsonProperty("request_id")
        private String requestId;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
    }
}
