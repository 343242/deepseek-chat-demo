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
 * PoC 8 — 验证 {@code ToolCallAdvisor.builder().disableMemory()} 不破坏 ReAct 循环（design §4.2）。
 * <p>
 * 背景：design §4.2 考虑给 agent 自建 {@code ToolCallAdvisor} 加 {@code .disableMemory()}（对齐全局 bean，
 * 避免 ToolCallAdvisor 与 MessageChatMemoryAdvisor 双重管理历史）。但该 ToolCallAdvisor 在
 * {@code buildAdvisorChain} 中为阻塞/流式共享，且当前阻塞态（{@code AgentModeStrategy:159-164}）未设置。
 * 风险：{@code .disableMemory()} 是否会破坏 ReAct 的工具结果反馈（多轮 threading）？
 * <p>
 * 本 spike：阻塞 {@code .call()} + 流式 {@code .stream()} 两条路径，{@code ToolCallAdvisor} 均加
 * {@code .disableMemory()}，断言工具仍执行、多轮仍发生、最终答案正确 → 证明 {@code .disableMemory()} 安全。
 */
@DisplayName("PoC 8: .disableMemory() 下阻塞/流式 ReAct 循环仍完整")
class Poc8_DisableMemoryReactIntactTest {

    /** 状态化 stub：round1 返 tool_call，round2 返答案；阻塞 call() 与流式 stream() 共享 round 计数。 */
    static final class StubModel implements ChatModel {
        final AtomicInteger round = new AtomicInteger(0);
        final AtomicInteger callCount = new AtomicInteger(0);
        final AtomicInteger streamCount = new AtomicInteger(0);

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount.incrementAndGet();
            return round.incrementAndGet() == 1 ? toolCallResponse() : answerResponse();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCount.incrementAndGet();
            if (round.incrementAndGet() == 1) {
                ChatResponse thought = new ChatResponse(List.of(
                    new Generation(new AssistantMessage("Let me check the weather. "))));
                return Flux.just(thought, toolCallResponse());
            }
            return Flux.just(answerResponse());
        }

        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }

        private static ChatResponse toolCallResponse() {
            AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{\"city\":\"Paris\"}"))).build();
            return new ChatResponse(List.of(new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
        }

        private static ChatResponse answerResponse() {
            return new ChatResponse(List.of(new Generation(
                new AssistantMessage("The weather in Paris is sunny, 22C."),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
        }
    }

    private static ToolCallback[] weatherCallback(AtomicBoolean fired) {
        return new ToolCallback[]{ FunctionToolCallback.builder("getWeather",
                (Map<String, Object> input, ToolContext ctx) -> { fired.set(true); return "sunny, 22C"; })
            .description("get weather").inputType(Map.class).build() };
    }

    private static ToolCallAdvisor advisorWithDisableMemory(ToolCallback[] callbacks) {
        DefaultToolCallingManager mgr = DefaultToolCallingManager.builder()
            .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callbacks))).build();
        return ToolCallAdvisor.builder()
            .toolCallingManager(mgr).advisorOrder(2)
            .disableMemory()
            .build();
    }

    @Test
    @DisplayName("阻塞 .call() + .disableMemory()：工具仍执行、两轮、答案正确")
    void blockingReactIntactWithDisableMemory() {
        StubModel model = new StubModel();
        AtomicBoolean fired = new AtomicBoolean(false);
        ToolCallback[] callbacks = weatherCallback(fired);

        ChatResponse resp = ChatClient.builder(model).build().prompt()
            .user("weather in Paris?")
            .advisors(advisorWithDisableMemory(callbacks))
            .options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build())
            .call().chatResponse();

        String content = resp == null ? "" : resp.getResult().getOutput().getText();
        System.out.println("POC8_BLOCK fired=" + fired.get() + " callCount=" + model.callCount.get()
            + " content=" + content);

        assertThat(fired.get()).as("阻塞：工具应执行").isTrue();
        assertThat(model.callCount.get()).as("阻塞：至少 2 轮（ReAct threading 完整）").isGreaterThanOrEqualTo(2);
        assertThat(content).as("阻塞：最终答案正确").contains("sunny");
    }

    @Test
    @DisplayName("流式 .stream() + .disableMemory()：工具仍执行、两轮全流式、答案流到消费方")
    void streamingReactIntactWithDisableMemory() {
        StubModel model = new StubModel();
        AtomicBoolean fired = new AtomicBoolean(false);
        ToolCallback[] callbacks = weatherCallback(fired);

        List<String> pieces = ChatClient.builder(model).build().prompt()
            .user("weather in Paris?")
            .advisors(advisorWithDisableMemory(callbacks))
            .options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build())
            .stream().content().collectList().block();

        String joined = pieces == null ? "" : String.join("", pieces);
        System.out.println("POC8_STREAM fired=" + fired.get() + " streamCount=" + model.streamCount.get()
            + " joined=" + joined);

        assertThat(fired.get()).as("流式：工具应执行").isTrue();
        assertThat(model.streamCount.get()).as("流式：至少 2 轮（ReAct threading 完整）").isGreaterThanOrEqualTo(2);
        assertThat(joined).as("流式：最终答案流到消费方").contains("sunny");
    }
}
