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
 * PoC 6 — Spring AI 1.1.6 流式 ToolCallAdvisor 是否驱动多轮 ReAct（agent-streaming-react option-A 可行性 spike）。
 * <p>
 * 验证命题：给定一个自定义 {@link ChatModel}，其 {@code stream(Prompt)} 在轮末发一个携带完整
 * {@code AssistantMessage.toolCalls} + finishReason=tool_calls 的汇总包，Spring AI 的
 * {@link ToolCallAdvisor#adviseStream} 能否<b>自动执行工具 + 喂回 + 再流一轮</b>，把最终答案流到消费方——
 * 即多轮流式 ReAct 无需 strategy 层自研。
 * <p>
 * 本 stub 不经 {@code ChatModelAdapter}，直接隔离验证 Spring AI 的流式工具循环契约。
 * stub 行为模拟"option A 下 ChatModelAdapter.stream() 修好（回灌 tool_calls/finishReason）之后"的形态：
 * <ul>
 *   <li>round 1：流出 thought 文本 + 末包带完整 toolCalls（finishReason=tool_calls）</li>
 *   <li>round 2：流出最终答案（finishReason=stop）</li>
 * </ul>
 * 结构镜像已验证通过的 {@code Poc5_FullChainAgentToolInvocationTest}（阻塞版），仅把 {@code .call()} 换成
 * {@code .stream().content()}。
 */
@DisplayName("PoC 6: Spring AI 1.1.6 流式 ToolCallAdvisor 驱动多轮 ReAct（option-A 可行性）")
class Poc6_SpringAiStreamingToolManagerTest {

    /** 自定义 ChatModel stub：模拟 option-A 下适配器 stream 修好后的行为。 */
    static final class StubStreamingToolChatModel implements ChatModel {
        final AtomicInteger round = new AtomicInteger(0);
        final AtomicInteger streamCount = new AtomicInteger(0);
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount.incrementAndGet();
            return blockingResponse(round.incrementAndGet());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCount.incrementAndGet();
            return streamResponse(round.incrementAndGet());
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private ChatResponse blockingResponse(int r) {
            if (r == 1) {
                return toolCallResponse();
            }
            return answerResponse();
        }

        private Flux<ChatResponse> streamResponse(int r) {
            if (r == 1) {
                // 流出 thought 文本（验证中间文本能流到消费方）+ 末包完整 toolCalls
                ChatResponse thought = new ChatResponse(List.of(
                    new Generation(new AssistantMessage("Let me check the weather. "))));
                return Flux.just(thought, toolCallResponse());
            }
            return Flux.just(answerResponse());
        }

        private static ChatResponse toolCallResponse() {
            AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                    "call_1", "function", "getWeather", "{\"city\":\"Paris\"}")))
                .build();
            return new ChatResponse(List.of(new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
        }

        private static ChatResponse answerResponse() {
            return new ChatResponse(List.of(new Generation(
                new AssistantMessage("The weather in Paris is sunny, 22C."),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
        }
    }

    @Test
    @DisplayName("ToolCallAdvisor.adviseStream 自动执行工具并多轮流式 ReAct")
    void streamingToolManagerDrivesReact() {
        StubStreamingToolChatModel model = new StubStreamingToolChatModel();
        AtomicBoolean fired = new AtomicBoolean(false);

        ToolCallback weather = FunctionToolCallback.builder("getWeather",
                (Map<String, Object> input, ToolContext ctx) -> {
                    fired.set(true);
                    return "sunny, 22C";
                })
            .description("get weather for a city")
            .inputType(Map.class)
            .build();
        ToolCallback[] callbacks = { weather };

        DefaultToolCallingManager mgr = DefaultToolCallingManager.builder()
            .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callbacks)))
            .build();
        ToolCallAdvisor advisor = ToolCallAdvisor.builder()
            .toolCallingManager(mgr)
            .advisorOrder(2)
            .build();

        List<String> pieces = ChatClient.builder(model).build().prompt()
            .user("What's the weather in Paris?")
            .advisors(advisor)
            .options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build())
            .stream()
            .content()
            .collectList()
            .block();

        String joined = pieces == null ? "" : String.join("", pieces);
        System.out.println("POC_RESULT streamToolFired=" + fired.get()
            + " streamCount=" + model.streamCount.get()
            + " callCount=" + model.callCount.get()
            + " round=" + model.round.get()
            + " pieces=" + pieces
            + " joined=" + joined);

        assertThat(fired.get())
            .as("工具应被 ToolCallAdvisor 在流式路径执行").isTrue();
        assertThat(joined)
            .as("最终答案应流到消费方").contains("sunny");
        assertThat(model.streamCount.get() + model.callCount.get())
            .as("至少 2 轮模型调用（证明 ReAct 递归）")
            .isGreaterThanOrEqualTo(2);
    }
}
