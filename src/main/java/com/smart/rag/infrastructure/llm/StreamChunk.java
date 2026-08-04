package com.smart.rag.infrastructure.llm;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 流式 chunk 载体 — 跨边界传递 text / tool delta / finishReason / usage（design §2）。
 * <p>
 * SPI 单轨 {@code Flux<StreamChunk>}；Spring AI 边界由 {@code ChatModelAdapter#stream}
 * 投影成带 tool_calls 的 {@code Flux<ChatResponse>}（喂给 {@code ToolCallAdvisor.adviseStream}）。
 * <p>
 * <b>不引入 Spring AI 类型</b>：与 {@link ChatCapable} 同持 ISP 纯净契约；{@code usage} 用
 * {@link LlmResponse.TokenUsage}（同包），由 {@code ChatModelAdapter} 在边界转 Spring AI Usage。
 * <p>
 * <b>P0a 状态</b>：仅 {@link #text} 被 {@code GenericChatClient} 占位填充（末端
 * {@code .map(s -> new StreamChunk(s, null, null, null))}）。{@code toolDelta} / {@code finishReason} /
 * {@code usage} 的真正填充在 P0b（GenericChatClient SSE 三态解析）。
 */
public record StreamChunk(
    @Nullable String text,
    @Nullable List<ToolCallDelta> toolCalls,
    @Nullable FinishReason finishReason,
    @Nullable LlmResponse.TokenUsage usage,
    @Nullable String reasoningContent
) {

    /** 向后兼容：旧 4 参签名（无 reasoningContent） */
    public StreamChunk(@Nullable String text, @Nullable List<ToolCallDelta> toolCalls,
                       @Nullable FinishReason finishReason, @Nullable LlmResponse.TokenUsage usage) {
        this(text, toolCalls, finishReason, usage, null);
    }

    public boolean hasText() { return text != null && !text.isEmpty(); }

    public boolean hasToolCall() { return toolCalls != null && !toolCalls.isEmpty(); }

    public boolean hasReasoning() { return reasoningContent != null && !reasoningContent.isEmpty(); }

    /** 单个 tool call 的流式分片（OpenAI 按 index 分片，arguments 为流式 JSON 片段）。 */
    public record ToolCallDelta(int index, @Nullable String id,
                                @Nullable String name, @Nullable String arguments) {}

    public enum FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER }
}
