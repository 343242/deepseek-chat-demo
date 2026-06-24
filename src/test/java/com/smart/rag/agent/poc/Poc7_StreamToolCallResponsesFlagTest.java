package com.smart.rag.agent.poc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC 7 — 钉死 {@code ToolCallAdvisor.Builder.streamToolCallResponses(boolean)} 在本系统消费路径
 * （{@code .stream().content()}，只取 AssistantMessage 文本）下的真实语义。
 * <p>
 * design §4.1 基于 Spring AI 文档措辞决定 Agent 流式设 {@code false}（"仅最终答案流到消费方"）。
 * 但文档有两义性：(A) 只过滤工具响应包、中间轮模型文本仍流；(B) 缓冲所有中间轮、只流最后一轮。
 * 且本系统中间轮通常 content 为空 + 工具响应非 AssistantMessage → {@code .content()} 可能两模式无差。
 * <p>
 * 本 spike 用同 stub（轮1 thought+tool_calls / 轮2 答案）分别跑 true/false，实证 {@code .content()} 收到什么。
 * 判据：false 下轮1 "Let me check the weather. " 是否仍到达消费方。
 * <ul>
 *   <li>仍到达 → 解读 A，§4.1 "干净输出" 收益落空 → 应弃用 false，用默认 true。</li>
 *   <li>不到达（只剩轮2答案）→ 解读 B，干净但有 TTFT 死寂代价（多工具场景）→ 需权衡。</li>
 * </ul>
 */
@DisplayName("PoC 7: streamToolCallResponses(true/false) 在 .content() 下的真实语义")
class Poc7_StreamToolCallResponsesFlagTest {

    /** 同 Poc6 stub：轮1 thought+tool_calls，轮2 答案。 */
    static final class StubModel implements ChatModel {
        final AtomicInteger round = new AtomicInteger(0);
        final AtomicInteger streamCount = new AtomicInteger(0);

        @Override public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCount.incrementAndGet();
            int r = round.incrementAndGet();
            if (r == 1) {
                ChatResponse thought = new ChatResponse(List.of(
                    new Generation(new AssistantMessage("Let me check the weather. "))));
                return Flux.just(thought, toolCallResponse());
            }
            return Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("The weather in Paris is sunny, 22C."),
                ChatGenerationMetadata.builder().finishReason("stop").build()))));
        }

        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }

        private static ChatResponse toolCallResponse() {
            AssistantMessage msg = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                    "call_1", "function", "getWeather", "{\"city\":\"Paris\"}"))).build();
            return new ChatResponse(List.of(new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
        }
    }

    private record RunResult(boolean fired, int streamCount, List<String> pieces, String joined) {}

    private RunResult runWith(boolean streamToolCallResponses) {
        StubModel model = new StubModel();
        AtomicBoolean fired = new AtomicBoolean(false);
        ToolCallback weather = FunctionToolCallback.builder("getWeather",
                (Map<String, Object> input, ToolContext ctx) -> { fired.set(true); return "sunny, 22C"; })
            .description("get weather").inputType(Map.class).build();
        ToolCallback[] callbacks = { weather };

        DefaultToolCallingManager mgr = DefaultToolCallingManager.builder()
            .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callbacks))).build();
        ToolCallAdvisor advisor = ToolCallAdvisor.builder()
            .toolCallingManager(mgr).advisorOrder(2)
            .streamToolCallResponses(streamToolCallResponses)
            .build();

        List<String> pieces = ChatClient.builder(model).build().prompt()
            .user("weather in Paris?")
            .advisors(advisor)
            .options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build())
            .stream().content().collectList().block();

        String joined = pieces == null ? "" : String.join("", pieces);
        return new RunResult(fired.get(), model.streamCount.get(), pieces, joined);
    }

    @Test
    @DisplayName("对比 streamToolCallResponses true vs false：.content() 实际收到什么")
    void compareTrueVsFalse() {
        RunResult t = runWith(true);
        RunResult f = runWith(false);

        boolean round1ThoughtInTrue = t.joined.contains("Let me check");
        boolean round1ThoughtInFalse = f.joined.contains("Let me check");
        boolean answerInTrue = t.joined.contains("sunny");
        boolean answerInFalse = f.joined.contains("sunny");

        System.out.println("POC7_COMPARE");
        System.out.println("  true  : fired=" + t.fired + " streamCount=" + t.streamCount
            + " round1Thought=" + round1ThoughtInTrue + " answer=" + answerInTrue
            + " pieces=" + t.pieces);
        System.out.println("  false : fired=" + f.fired + " streamCount=" + f.streamCount
            + " round1Thought=" + round1ThoughtInFalse + " answer=" + answerInFalse
            + " pieces=" + f.pieces);
        System.out.println("  VERDICT: " + (round1ThoughtInFalse
            ? "解读A — false 不过滤中间轮模型文本，§4.1 干净输出收益落空"
            : "解读B — false 仅最终答案到达，干净但有 TTFT 死寂代价"));

        // 不变量：两模式都应执行工具并至少把最终答案送到消费方
        assertThat(t.fired).as("true: 工具应执行").isTrue();
        assertThat(f.fired).as("false: 工具应执行").isTrue();
        assertThat(answerInTrue).as("true: 最终答案应到达").isTrue();
        assertThat(answerInFalse).as("false: 最终答案应到达").isTrue();
    }
}
