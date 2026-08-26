package com.smart.rag.mode;

/**
 * 流式帧 — 区分正文片段与思考过程片段（reasoning_content）。
 * <p>
 * Spring AI 的 {@code .stream().content()} 只返回 {@code Flux<String>} 文本，
 * 丢弃 {@code AssistantMessage.metadata} 中的 {@code reasoning_content}（思考过程），
 * 且 {@code .filter(hasLength)} 会把 reasoning-only chunk（文本为空）整体过滤掉。
 * 改用 {@code .stream().chatResponse()} 后，strategy 层手动拆出 text / reasoning，
 * 包装为本 record 下发，供 {@code SseStreamBridge} 按 {@link #kind()} 分发到不同 SSE event。
 *
 * @param kind    帧种类：CONTENT（正文，默认 data 帧）、REASONING（思考，event:reasoning 帧）
 *                或 RESET（模型切换标记，event:reset 帧，design llm-resilience-optimization WS5）
 * @param payload 帧文本片段（RESET 为 {"from","to"} JSON）
 */
public record StreamFrame(Kind kind, String payload) {

    public enum Kind { CONTENT, REASONING, RESET }

    /** 正文帧工厂 */
    public static StreamFrame content(String text) {
        return new StreamFrame(Kind.CONTENT, text);
    }

    /** 思考帧工厂 */
    public static StreamFrame reasoning(String text) {
        return new StreamFrame(Kind.REASONING, text);
    }

    /**
     * 模型切换标记帧工厂（WS5）：前端收到 {@code event: reset} 应清空已累积回答缓冲
     * （含 reasoning 缓冲），随后内容来自新模型。旧前端忽略未知事件 = 行为同现状，无回归。
     */
    public static StreamFrame reset(String fromCandidateId, String toCandidateId) {
        return new StreamFrame(Kind.RESET, "{\"from\": \"" + fromCandidateId
            + "\", \"to\": \"" + toCandidateId + "\"}");
    }

    /** 是否为正文帧 */
    public boolean isContent() { return kind == Kind.CONTENT; }

    /** 是否为思考帧 */
    public boolean isReasoning() { return kind == Kind.REASONING; }

    /** 是否为模型切换标记帧 */
    public boolean isReset() { return kind == Kind.RESET; }
}
