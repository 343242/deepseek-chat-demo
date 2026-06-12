package com.smart.rag.infrastructure.llm;

import java.util.Map;

/**
 * 模型候选声明——sealed interface
 * <p>
 * 每个候选声明且仅声明一种能力（由所属 {@code ModelGroup} 决定）。
 * 三种子类型：{@link ChatCandidate}、{@link EmbeddingCandidate}、{@link RerankCandidate}。
 * <p>
 * <b>公共方法</b>：所有子类型都实现 {@code id()}、{@code provider()}、{@code model()}、
 * {@code priority()}、{@code capability()}、{@code enabled()}、{@code params()}。
 * <p>
 * <b>能力特定方法</b>：
 * <ul>
 *   <li>{@code supportsThinking()} / {@code supportsStreaming()} — 仅 {@code ChatCandidate} 返回有意义的值</li>
 *   <li>{@code dimension()} — 仅 {@code EmbeddingCandidate} 返回有意义的值</li>
 * </ul>
 * 基类默认返回安全值（false / 0），子类覆写为实际值——避免调用方强制转型。
 */
public sealed interface ModelCandidate
    permits AbstractModelCandidate {

    /** 候选唯一标识（用于 default-model / deep-thinking-model 引用） */
    String id();

    /** 引用的供应商 id */
    String provider();

    /** 发送给 LLM API 的原始模型名 */
    String model();

    /** 优先级，数字越小越优先 */
    int priority();

    /** 该候选声明的能力 */
    LlmCapability capability();

    /** 是否启用（默认 true） */
    boolean enabled();

    /** 默认调用参数 */
    Map<String, Object> params();

    /** 是否支持深度思考（仅 ChatCandidate 返回 true/false，其他返回 false） */
    default boolean supportsThinking() { return false; }

    /** 向量维度（仅 EmbeddingCandidate 返回 > 0，其他返回 0） */
    default int dimension() { return 0; }

    /** 是否支持流式输出（仅 ChatCandidate 返回 true/false，其他返回 false） */
    default boolean supportsStreaming() { return false; }
}
