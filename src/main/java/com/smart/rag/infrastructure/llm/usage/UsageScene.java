package com.smart.rag.infrastructure.llm.usage;

/**
 * LLM 调用场景 — 用量采集的业务归因维度。
 * <p>
 * 每个枚举值对应一类独立的模型调用链路，落库到 {@code usage_event.scene} 列，
 * 供按场景聚合/过滤。新增 LLM 调用链路接入 {@code ChatModelAssembler} 时补充枚举值。
 * <p>
 * 未接入的已知链路：查询改写（REWRITE）——其 ChatClient 深藏在 Spring AI RAG
 * 单例 Advisor 结构中，per-request 用户归因需重构 RAG 组装链，暂不采集。
 */
public enum UsageScene {

    /** 聊天主链路（阻塞式/流式，SIMPLE 与 MULTI_TURN 模式，含 fallback 各候选） */
    CHAT,

    /** Agent 模式 ReAct 循环（每轮一次模型调用） */
    AGENT,

    /** Agent 意图分类（每次 Agent 请求前置一次阻塞/流式调用） */
    INTENT
}
