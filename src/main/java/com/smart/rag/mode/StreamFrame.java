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
 * @param kind    帧种类：CONTENT（正文，默认 data 帧）或 REASONING（思考，event:reasoning 帧）
 * @param payload 帧文本片段
 */
public record StreamFrame(Kind kind, String payload) {

    public enum Kind { CONTENT, REASONING }

    /** 正文帧工厂 */
    public static StreamFrame content(String text) {
        return new StreamFrame(Kind.CONTENT, text);
    }

    /** 思考帧工厂 */
    public static StreamFrame reasoning(String text) {
        return new StreamFrame(Kind.REASONING, text);
    }

    /** 是否为正文帧 */
    public boolean isContent() { return kind == Kind.CONTENT; }

    /** 是否为思考帧 */
    public boolean isReasoning() { return kind == Kind.REASONING; }
}
