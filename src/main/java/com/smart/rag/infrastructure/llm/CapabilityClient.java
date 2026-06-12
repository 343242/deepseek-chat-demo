package com.smart.rag.infrastructure.llm;

/**
 * 能力客户端根接口
 * <p>
 * 所有 LLM 能力客户端（Chat / Embedding / Rerank）的公共契约。
 * 不定义具体 LLM 调用方法——通过 Registry 的类型查询获取具体能力接口。
 * <p>
 * <b>设计决策</b>：接口不暴露 {@code ModelCandidate} 引用。
 * {@code ModelCandidate} 是创建客户端的输入参数，不应成为接口契约的一部分——
 * 避免接口与数据模型的循环耦合，也使客户端可以在测试中脱离 ModelCandidate 独立使用。
 */
public interface CapabilityClient extends AutoCloseable {

    /** 候选唯一标识（对应 YAML candidate.id） */
    String candidateId();

    /** 供应商 ID（对应 YAML candidate.provider） */
    String providerId();

    /** 发送给 LLM API 的原始模型名（对应 YAML candidate.model） */
    String modelName();

    /** 该客户端声明的能力（一对一） */
    LlmCapability capability();

    /** 该客户端是否可用 */
    boolean isAvailable();

    @Override
    default void close() {}
}
