package com.smart.rag.agent.poc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
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
 * PoC 9 — 验证 {@link BaseAdvisor#before} 在多轮 ReAct 下的调用频次（design §4.3/§7 核心假设证伪/证实）。
 * <p>
 * <b>背景</b>：design §4.3/§7 假设 {@code AgentSystemPromptAdvisor.before()}（implements {@link BaseAdvisor}）
 * 每轮 ReAct 都被调用，用作"抛 {@code GuardrailHardStopException} 关连接"硬上界的检查点。
 * <p>
 * <b>字节码反编译（Spring AI 1.1.6）强指示</b>：{@link BaseAdvisor#adviseStream} 把 {@code before()} 映射在
 * <b>单发 Mono</b> 上（{@code lambda$adviseStream$0}）→ before() 每次 stream 订阅<b>只触发一次</b>；
 * {@link ToolCallAdvisor} 的多轮循环由 {@code internalStream}/{@code handleToolCallRecursion} 内部驱动
 * （own {@code doBeforeStream}/{@code doAfterStream} 模板方法），续轮走 {@code chain.copy(this).nextStream()}
 * 只重入下游链拿 model stream，<b>不重经过上游 BaseAdvisor</b>。
 * <p>
 * <b>本 spike 判别命题</b>：
 * <ul>
 *   <li>若 {@code baseBeforeCount == 1} 且模型跑了 ≥2 轮 → <b>证伪 design 假设</b>（before 仅 round 1 触发，
 *       per-round 硬上界失效；正确的 per-round hook 应是 {@code ToolCallAdvisor.doBeforeStream}）。</li>
 *   <li>若 {@code baseBeforeCount == 模型轮数} → design 假设成立。</li>
 * </ul>
 * 同时验阻塞 {@code .call()}（同样的 {@code BaseAdvisor.adviseCall} + {@code ToolCallAdvisor.adviseCall} 链），
 * 顺带查"阻塞态既有迭代/token 护栏是否也是 no-op"。
 * <p>
 * 结构镜像 {@code Poc6_SpringAiStreamingToolManagerTest}（流式）/ {@code Poc8_DisableMemoryReactIntactTest}
 * （阻塞+流式），仅在前置位多挂一个计数型 {@link BaseAdvisor}。
 */
@DisplayName("PoC 9: BaseAdvisor.before() 多轮 ReAct 调用频次（design §4.3/§7 假设判别）")
class Poc9_AdvisorBeforePerRoundTest {

    /**
     * 计数型 BaseAdvisor：模拟 {@code AgentSystemPromptAdvisor} 的位置（order=1，先于 ToolCallAdvisor）。
     * before/after 各加一计数器，request/response 透传。继承 {@link BaseAdvisor} 的 default
     * {@code adviseStream}/{@code adviseCall}（正是要测的"before 映射在单发 Mono"那段实现）。
     */
    static final class CountingBaseAdvisor implements BaseAdvisor {
        final AtomicInteger beforeCount = new AtomicInteger(0);
        final AtomicInteger afterCount = new AtomicInteger(0);

        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            beforeCount.incrementAndGet();
            return request;
        }

        @Override
        public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            afterCount.incrementAndGet();
            return response;
        }

        @Override
        public int getOrder() { return 1; }
    }

    /** 状态化 stub：round1 返 tool_call + thought，round2 返答案；阻塞/流式各自 new 独立计数。 */
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

    private static ToolCallAdvisor toolCallAdvisor(ToolCallback[] callbacks) {
        DefaultToolCallingManager mgr = DefaultToolCallingManager.builder()
            .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callbacks))).build();
        return ToolCallAdvisor.builder().toolCallingManager(mgr).advisorOrder(2).build();
    }

    /** 打印人类可读判决（测试恒 GREEN，结论从输出读，避免判别实验因结论方向不同而误红）。 */
    private static void verdict(String tag, int beforeCount, int modelRounds) {
        boolean holds = (beforeCount == modelRounds);
        System.out.println("POC9_" + tag + " VERDICT: beforeCount=" + beforeCount + " modelRounds=" + modelRounds
            + " → design §4.3 per-round-before assumption "
            + (holds ? "HOLDS (before fired each round)" : "FALSIFIED (before fired once only)"));
    }

    @Test
    @DisplayName("流式：模型跑多轮时 before() 是否每轮触发")
    void streamingBeforeCallFrequency() {
        StubModel model = new StubModel();
        CountingBaseAdvisor base = new CountingBaseAdvisor();
        AtomicBoolean fired = new AtomicBoolean(false);
        ToolCallback[] callbacks = weatherCallback(fired);

        List<String> pieces = ChatClient.builder(model).build().prompt()
            .user("weather in Paris?")
            .advisors(base, toolCallAdvisor(callbacks))
            .options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build())
            .stream().content().collectList().block();

        String joined = pieces == null ? "" : String.join("", pieces);
        int modelRounds = model.streamCount.get() + model.callCount.get();
        System.out.println("POC9_STREAM baseBefore=" + base.beforeCount.get()
            + " baseAfter=" + base.afterCount.get()
            + " streamCount=" + model.streamCount.get()
            + " callCount=" + model.callCount.get()
            + " modelRounds=" + modelRounds
            + " fired=" + fired.get()
            + " joined=" + joined);
        verdict("STREAM", base.beforeCount.get(), modelRounds);

        assertThat(fired.get()).as("工具应被执行（多轮 ReAct 发生）").isTrue();
        assertThat(modelRounds).as("模型应至少跑 2 轮（ReAct 递归）").isGreaterThanOrEqualTo(2);
        // 判别：before 要么 1 次（#2 成立）要么 == modelRounds（design 假设成立）。两者择一即合理。
        assertThat(base.beforeCount.get())
            .as("before() 应为 1（仅 round1，design 假设证伪）或 == modelRounds（每轮，假设成立）")
            .isIn(1, modelRounds);
    }

    @Test
    @DisplayName("阻塞：模型跑多轮时 before() 是否每轮触发（查既有阻塞护栏是否也是 no-op）")
    void blockingBeforeCallFrequency() {
        StubModel model = new StubModel();
        CountingBaseAdvisor base = new CountingBaseAdvisor();
        AtomicBoolean fired = new AtomicBoolean(false);
        ToolCallback[] callbacks = weatherCallback(fired);

        ChatResponse resp = ChatClient.builder(model).build().prompt()
            .user("weather in Paris?")
            .advisors(base, toolCallAdvisor(callbacks))
            .options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build())
            .call().chatResponse();

        String content = resp == null ? "" : resp.getResult().getOutput().getText();
        int modelRounds = model.streamCount.get() + model.callCount.get();
        System.out.println("POC9_BLOCK baseBefore=" + base.beforeCount.get()
            + " baseAfter=" + base.afterCount.get()
            + " callCount=" + model.callCount.get()
            + " modelRounds=" + modelRounds
            + " fired=" + fired.get()
            + " content=" + content);
        verdict("BLOCK", base.beforeCount.get(), modelRounds);

        assertThat(fired.get()).as("工具应被执行").isTrue();
        assertThat(modelRounds).as("模型应至少跑 2 轮").isGreaterThanOrEqualTo(2);
        assertThat(base.beforeCount.get())
            .as("阻塞 before() 应为 1 或 == modelRounds")
            .isIn(1, modelRounds);
    }
}
