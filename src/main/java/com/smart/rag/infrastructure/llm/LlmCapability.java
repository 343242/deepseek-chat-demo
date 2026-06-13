package com.smart.rag.infrastructure.llm;

/**
 * 模型能力声明
 * <p>
 * 每个 ModelCandidate 通过 capability 声明该模型支持的操作。
 * 调用方据此过滤可用客户端，Registry 据此构建按能力分类的索引。
 * <p>
 * 扩展方式：新增枚举值即可，不影响已有代码。
 * <p>
 * <b>CHAT 的流式支持</b>：CHAT 能力同时提供 {@code chat()}（阻塞）和 {@code chatStream()}（流式）方法。
 * 模型是否支持流式由 {@code ModelCandidate.supportsStreaming()} 字段声明（YAML: {@code supports-streaming: true}），
 * 未声明时 {@code chatStream()} 抛出 {@code UnsupportedOperationException}。
 */
public enum LlmCapability {
    /** 对话能力（同时支持阻塞 chat() 和流式 chatStream()，流式支持由 ModelCandidate.supportsStreaming 声明） */
    CHAT,
    /** 向量嵌入 */
    EMBEDDING,
    /** 重排序 */
    RERANKING;

    /**
     * 对应的 YAML 配置 key（用于 {@code app.llm.providers.<id>.endpoints.<yamlKey>}）。
     * 大多数能力直接用枚举名小写；个别能力（如 RERANKING → "rerank"）遵循社区命名约定。
     */
    public String yamlKey() {
        return this == RERANKING ? "rerank" : name().toLowerCase();
    }
}
