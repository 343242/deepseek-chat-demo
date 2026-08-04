package com.smart.rag.infrastructure.llm.client.generic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.llm.StreamChunk;
import okhttp3.Call;
import okio.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P1 — GenericChatClient SSE 三态解析单测（design §3，直接测 readSse）。
 * <p>
 * 不绕 OkHttp 整链：readSse 改 static 接 ObjectMapper 参数，测试喂 fake SSE Buffer（okio.Buffer）。
 * 覆盖：文本流 / tool_call 首片+arguments 分片累积 / 多工具并行 index / usage 末包 /
 * [DONE] 兜底 / finishReason 后 complete。
 */
@DisplayName("GenericChatClient SSE 三态解析（readSse）")
class GenericChatClientSseTest {

    // ====== helpers ======

    private static List<StreamChunk> parse(String... sseLines) {
        StringBuilder sb = new StringBuilder();
        for (String l : sseLines) sb.append("data: ").append(l).append("\n\n");
        Buffer buf = new Buffer().writeUtf8(sb.toString());
        return Flux.<StreamChunk>create(sink -> {
            try {
                GenericChatClient.readSse(new ObjectMapper(), buf, mock(Call.class), sink);
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }).collectList().block();
    }

    /** 取最后一个带 finishReason 的汇总包（轮末）。 */
    private static StreamChunk roundSummary(List<StreamChunk> chunks) {
        StreamChunk last = null;
        for (StreamChunk c : chunks) if (c.finishReason() != null || c.hasToolCall() || c.usage() != null) last = c;
        return last;
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** delta.content 文本 chunk。 */
    private static String txt(String content) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":" + q(content) + "},\"finish_reason\":null}]}";
    }

    /** delta.reasoning_content 思考片段 chunk。 */
    private static String reasoning(String content) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":" + q(content) + "},\"finish_reason\":null}]}";
    }

    /** tool_call 首片（index/id/name + arguments 首段）。 */
    private static String tcFirst(int idx, String id, String name, String argsPart) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":" + idx
            + ",\"id\":" + q(id) + ",\"type\":\"function\",\"function\":{\"name\":" + q(name)
            + ",\"arguments\":" + q(argsPart) + "}}]},\"finish_reason\":null}]}";
    }

    /** tool_call arguments 后续分片（仅 index + arguments 片段）。 */
    private static String tcArgs(int idx, String argsPart) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":" + idx
            + ",\"function\":{\"arguments\":" + q(argsPart) + "}}]},\"finish_reason\":null}]}";
    }

    /** 末包 finish_reason（可选 usage）。 */
    private static String finish(String reason, String usageJson) {
        String u = usageJson != null ? ",\"usage\":" + usageJson : "";
        return "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":" + q(reason) + "}]" + u + "}";
    }

    // ====== 场景 ======

    @Test
    @DisplayName("纯文本流：content chunk 即时透传，末包 finishReason=STOP 汇总")
    void textOnlyStream() {
        List<StreamChunk> chunks = parse(txt("Hello"), txt(" world"), finish("stop", null));

        // 2 个 text chunk（即时发，保 TTFT）+ 1 个 STOP 汇总包
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).hasText()).isTrue();
        assertThat(chunks.get(0).text()).isEqualTo("Hello");
        assertThat(chunks.get(1).text()).isEqualTo(" world");
        StreamChunk end = roundSummary(chunks);
        assertThat(end.finishReason()).isEqualTo(StreamChunk.FinishReason.STOP);
        assertThat(end.hasToolCall()).isFalse();
    }

    @Test
    @DisplayName("tool_call 首片 + arguments 分片：轮末汇总包含完整合并后的 toolCalls")
    void toolCallFragmentsMergedIntoSummary() {
        List<StreamChunk> chunks = parse(
            tcFirst(0, "call_1", "hybridSearch", "{\"q\":"),
            tcArgs(0, "\"Paris\"}"),
            finish("tool_calls", null));

        // 首片 + 分片不即时发（累积 acc），仅轮末发 1 个汇总包
        assertThat(chunks).hasSize(1);
        StreamChunk end = chunks.get(0);
        assertThat(end.finishReason()).isEqualTo(StreamChunk.FinishReason.TOOL_CALLS);
        assertThat(end.toolCalls()).hasSize(1);
        StreamChunk.ToolCallDelta tc = end.toolCalls().get(0);
        assertThat(tc.index()).isEqualTo(0);
        assertThat(tc.id()).isEqualTo("call_1");
        assertThat(tc.name()).isEqualTo("hybridSearch");
        // arguments 跨片拼接
        assertThat(tc.arguments()).isEqualTo("{\"q\":\"Paris\"}");
    }

    @Test
    @DisplayName("多工具并行：index 0/1 各自累积，汇总包按 index 顺序输出")
    void multiToolParallelByIndex() {
        List<StreamChunk> chunks = parse(
            tcFirst(0, "c0", "search", "{"),
            tcFirst(1, "c1", "calc", "{"),
            tcArgs(0, "\"q\":1}"),
            tcArgs(1, "\"x\":2}"),
            finish("tool_calls", null));

        StreamChunk end = roundSummary(chunks);
        assertThat(end.toolCalls()).hasSize(2);
        assertThat(end.toolCalls().get(0).index()).isEqualTo(0);
        assertThat(end.toolCalls().get(0).name()).isEqualTo("search");
        assertThat(end.toolCalls().get(0).arguments()).isEqualTo("{\"q\":1}");
        assertThat(end.toolCalls().get(1).index()).isEqualTo(1);
        assertThat(end.toolCalls().get(1).name()).isEqualTo("calc");
        assertThat(end.toolCalls().get(1).arguments()).isEqualTo("{\"x\":2}");
    }

    @Test
    @DisplayName("usage 末包：轮末汇总包携带 TokenUsage")
    void usageCarriedInSummary() {
        List<StreamChunk> chunks = parse(
            txt("answer"),
            finish("stop", "{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}"));

        StreamChunk end = roundSummary(chunks);
        assertThat(end.usage()).isNotNull();
        assertThat(end.usage().totalTokens()).isEqualTo(30);
        assertThat(end.usage().promptTokens()).isEqualTo(10);
        assertThat(end.usage().completionTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("finish_reason=tool_calls 后流 complete（后续 [DONE] 不再产出）")
    void finishReasonTerminatesStream() {
        List<StreamChunk> chunks = parse(
            tcFirst(0, "c0", "search", "{}"),
            finish("tool_calls", null));  // readSse 在 finish_reason 后 return

        // 只有汇总包 1 个（finish_reason 后 return，不再读）
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).finishReason()).isEqualTo(StreamChunk.FinishReason.TOOL_CALLS);
    }

    @Test
    @DisplayName("AC6：reasoning_content delta 以独立 chunk 即时下发，先于 content")
    void reasoningContentStreamed() {
        List<StreamChunk> chunks = parse(
            reasoning("首先分析问题"),
            txt("量子计算"),
            finish("stop", null));

        // reasoning chunk（仅 reasoningContent 非空，无 text）+ text chunk + STOP 汇总包
        assertThat(chunks).hasSize(3);
        StreamChunk r = chunks.get(0);
        assertThat(r.hasReasoning()).isTrue();
        assertThat(r.hasText()).isFalse();
        assertThat(r.reasoningContent()).isEqualTo("首先分析问题");
        assertThat(chunks.get(1).hasText()).isTrue();
        assertThat(chunks.get(1).text()).isEqualTo("量子计算");
        // 轮末汇总包携带累积 reasoning（供 R6 回传）
        assertThat(chunks.get(2).reasoningContent()).isEqualTo("首先分析问题");
    }

    @Test
    @DisplayName("AC6 扩展：多帧 reasoning delta 各自即时下发，轮末汇总包携带完整拼接值（非最后片段）")
    void reasoningAccumulatedInToolCallSummary() {
        List<StreamChunk> chunks = parse(
            reasoning("第1段"),
            reasoning("第2段"),
            tcFirst(0, "c0", "search", "{}"),
            finish("tool_calls", null));

        // 2 个即时 reasoning 片段 + 1 个轮末汇总包（toolCalls + 完整累积 reasoning）
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).reasoningContent()).isEqualTo("第1段");
        assertThat(chunks.get(1).reasoningContent()).isEqualTo("第2段");

        StreamChunk end = roundSummary(chunks);
        assertThat(end.finishReason()).isEqualTo(StreamChunk.FinishReason.TOOL_CALLS);
        assertThat(end.toolCalls()).hasSize(1);
        // 完整拼接，而非 last-writer-wins 的最后一个片段
        assertThat(end.reasoningContent()).isEqualTo("第1段第2段");
    }

    @Test
    @DisplayName("AC6 扩展：[DONE] 兜底路径同样携带完整累积 reasoning（guard 不跳过）")
    void reasoningAccumulatedThroughDoneFallback() {
        List<StreamChunk> chunks = parse(
            reasoning("A段"),
            reasoning("B段"),
            "[DONE]");

        // 2 个即时片段 + 1 个 [DONE] 轮末汇总包（fr/usage/toolCalls 皆 null，但 reasoning 非空 → guard 不跳过）
        assertThat(chunks).hasSize(3);
        StreamChunk end = chunks.get(2);
        assertThat(end.finishReason()).isNull();
        assertThat(end.usage()).isNull();
        assertThat(end.hasToolCall()).isFalse();
        assertThat(end.reasoningContent()).isEqualTo("A段B段");
    }

    @Test
    @DisplayName("AC6 扩展：同一 delta 同时含 reasoning_content 与 content → 两者都被提取")
    void reasoningAndContentSameFrame() {
        String frame = "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"思考\","
            + "\"content\":\"回答\"},\"finish_reason\":null}]}";
        List<StreamChunk> chunks = parse(frame, finish("stop", null));

        // reasoning chunk（即时，先于 content）+ text chunk + STOP 汇总包（携带累积 reasoning）
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).hasReasoning()).isTrue();
        assertThat(chunks.get(0).reasoningContent()).isEqualTo("思考");
        assertThat(chunks.get(0).hasText()).isFalse();
        assertThat(chunks.get(1).text()).isEqualTo("回答");
        assertThat(chunks.get(1).hasReasoning()).isFalse();
        assertThat(chunks.get(2).reasoningContent()).isEqualTo("思考");
    }
}
