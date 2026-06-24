package com.smart.rag.agent.poc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
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
import org.springframework.ai.model.tool.ToolCallingManager;
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
 * PoC 10 — 验证 {@link ToolCallAdvisor} 的 protected 模板方法在多轮 ReAct 下是否每轮触发（design §0 #2 修法前置）。
 * <p>
 * <b>背景</b>：{@code Poc9_AdvisorBeforePerRoundTest} 已铁证 {@code BaseAdvisor.before()} 仅 round 1 触发 →
 * design §4.3 原本"每轮 before 检查"的硬上界落点失效。字节码反编译（Spring AI 1.1.6）显示 {@link ToolCallAdvisor}
 * 的多轮循环由 {@code internalStream}/{@code handleToolCallRecursion} 内部驱动，每轮调自己的 protected 模板方法
 * （{@code doBeforeStream}/{@code doAfterStream}/...）。本 spike 判别：这些模板方法是否<b>每轮触发</b>——
 * 若是，则 P4b 的 per-round 硬上界检查可挂在此处（子类化 override {@code doBeforeStream}/{@code doBeforeCall}）。
 * <p>
 * <b>判别命题</b>（模型跑 2 轮）：
 * <ul>
 *   <li>若 {@code doBeforeStream == 轮数} → 每轮触发 → P4b 可挂 doBeforeStream（子类化路线成立）。</li>
 *   <li>若 {@code doBeforeStream == 1} → 也只 round 1 → P4b 必须改走边界层自治（{@code ChatModelAdapter.stream} 轮末自检）。</li>
 * </ul>
 * 预期（字节码推断）：{@code doInitializeLoop*} 调 1 次（入口），{@code doBeforeStream/doAfterStream} 每轮，
 * {@code doFinalizeLoop*} 调 1 次（结束）。实测以本 spike 输出为准。
 * <p>
 * 签名来源：{@code javap -p ToolCallAdvisor}（1.1.6），8 个 doXxx 均 protected，构造器 {@code protected ToolCallAdvisor(ToolCallingManager, int)}。
 */
@DisplayName("PoC 10: ToolCallAdvisor doBeforeStream/doBeforeCall 多轮触发频次（P4b hook 落点判别）")
class Poc10_ToolCallAdvisorDoBeforeStreamPerRound {

    /**
     * 计数型 ToolCallAdvisor：override 全部 8 个 protected 模板方法，各加计数器后透传 super。
     * {@code @Override} 充当签名校验——签名错则编译失败。
     */
    static final class CountingToolCallAdvisor extends ToolCallAdvisor {
        final AtomicInteger doBeforeStream = new AtomicInteger();
        final AtomicInteger doAfterStream = new AtomicInteger();
        final AtomicInteger doInitStream = new AtomicInteger();
        final AtomicInteger doFinalizeStream = new AtomicInteger();
        final AtomicInteger doBeforeCall = new AtomicInteger();
        final AtomicInteger doAfterCall = new AtomicInteger();
        final AtomicInteger doInitCall = new AtomicInteger();
        final AtomicInteger doFinalizeCall = new AtomicInteger();

        CountingToolCallAdvisor(ToolCallingManager mgr) { super(mgr, 2); }

        @Override protected ChatClientRequest doBeforeStream(ChatClientRequest r, StreamAdvisorChain c) {
            doBeforeStream.incrementAndGet(); return super.doBeforeStream(r, c);
        }
        @Override protected ChatClientResponse doAfterStream(ChatClientResponse r, StreamAdvisorChain c) {
            doAfterStream.incrementAndGet(); return super.doAfterStream(r, c);
        }
        @Override protected ChatClientRequest doInitializeLoopStream(ChatClientRequest r, StreamAdvisorChain c) {
            doInitStream.incrementAndGet(); return super.doInitializeLoopStream(r, c);
        }
        @Override protected Flux<ChatClientResponse> doFinalizeLoopStream(Flux<ChatClientResponse> f, StreamAdvisorChain c) {
            doFinalizeStream.incrementAndGet(); return super.doFinalizeLoopStream(f, c);
        }
        @Override protected ChatClientRequest doBeforeCall(ChatClientRequest r, CallAdvisorChain c) {
            doBeforeCall.incrementAndGet(); return super.doBeforeCall(r, c);
        }
        @Override protected ChatClientResponse doAfterCall(ChatClientResponse r, CallAdvisorChain c) {
            doAfterCall.incrementAndGet(); return super.doAfterCall(r, c);
        }
        @Override protected ChatClientRequest doInitializeLoop(ChatClientRequest r, CallAdvisorChain c) {
            doInitCall.incrementAndGet(); return super.doInitializeLoop(r, c);
        }
        @Override protected ChatClientResponse doFinalizeLoop(ChatClientResponse r, CallAdvisorChain c) {
            doFinalizeCall.incrementAndGet(); return super.doFinalizeLoop(r, c);
        }
    }

    /** 状态化 stub：round1 返 tool_call + thought，round2 返答案；阻塞/流式各自 new 独立计数。 */
    static final class StubModel implements ChatModel {
        final AtomicInteger round = new AtomicInteger(0);
        final AtomicInteger callCount = new AtomicInteger(0);
        final AtomicInteger streamCount = new AtomicInteger(0);

        @Override public ChatResponse call(Prompt prompt) {
            callCount.incrementAndGet();
            return round.incrementAndGet() == 1 ? toolCallResponse() : answerResponse();
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
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

    private static DefaultToolCallingManager mgr(ToolCallback[] callbacks) {
        return DefaultToolCallingManager.builder()
            .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callbacks))).build();
    }

    private static void verdict(String tag, String hook, int hookCount, int rounds) {
        boolean perRound = (hookCount == rounds);
        System.out.println("POC10_" + tag + " VERDICT: " + hook + "=" + hookCount + " rounds=" + rounds
            + " → " + (perRound ? "PER-ROUND (hook fires each round → P4b can mount here)"
                                : "ONCE-ONLY (hook fires round 1 → P4b must use boundary-layer autonomy)"));
    }

    @Test
    @DisplayName("流式：doBeforeStream/doAfterStream 是否每轮触发")
    void streamingDoBeforeStreamFiresPerRound() {
        StubModel model = new StubModel();
        AtomicBoolean fired = new AtomicBoolean(false);
        ToolCallback[] callbacks = weatherCallback(fired);
        CountingToolCallAdvisor tca = new CountingToolCallAdvisor(mgr(callbacks));

        List<String> pieces = ChatClient.builder(model).build().prompt()
            .user("weather in Paris?")
            .advisors(tca)
            .options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build())
            .stream().content().collectList().block();

        String joined = pieces == null ? "" : String.join("", pieces);
        int rounds = model.streamCount.get() + model.callCount.get();
        System.out.println("POC10_STREAM doInitStream=" + tca.doInitStream.get()
            + " doBeforeStream=" + tca.doBeforeStream.get()
            + " doAfterStream=" + tca.doAfterStream.get()
            + " doFinalizeStream=" + tca.doFinalizeStream.get()
            + " streamCount=" + model.streamCount.get()
            + " rounds=" + rounds
            + " fired=" + fired.get()
            + " joined=" + joined);
        verdict("STREAM", "doBeforeStream", tca.doBeforeStream.get(), rounds);

        assertThat(fired.get()).as("工具应被执行（多轮 ReAct 发生）").isTrue();
        assertThat(rounds).as("模型应至少跑 2 轮").isGreaterThanOrEqualTo(2);
        // 判别：doBeforeStream 要么 1（只 round1）要么 == rounds（每轮）。两者择一即合理。
        assertThat(tca.doBeforeStream.get())
            .as("doBeforeStream 应为 1（仅 round1）或 == rounds（每轮，P4b 可挂）")
            .isIn(1, rounds);
    }

    @Test
    @DisplayName("阻塞：doBeforeCall/doAfterCall 是否每轮触发")
    void blockingDoBeforeCallFiresPerRound() {
        StubModel model = new StubModel();
        AtomicBoolean fired = new AtomicBoolean(false);
        ToolCallback[] callbacks = weatherCallback(fired);
        CountingToolCallAdvisor tca = new CountingToolCallAdvisor(mgr(callbacks));

        ChatResponse resp = ChatClient.builder(model).build().prompt()
            .user("weather in Paris?")
            .advisors(tca)
            .options(ToolCallingChatOptions.builder().toolCallbacks(callbacks).build())
            .call().chatResponse();

        String content = resp == null ? "" : resp.getResult().getOutput().getText();
        int rounds = model.streamCount.get() + model.callCount.get();
        System.out.println("POC10_BLOCK doInitCall=" + tca.doInitCall.get()
            + " doBeforeCall=" + tca.doBeforeCall.get()
            + " doAfterCall=" + tca.doAfterCall.get()
            + " doFinalizeCall=" + tca.doFinalizeCall.get()
            + " callCount=" + model.callCount.get()
            + " rounds=" + rounds
            + " fired=" + fired.get()
            + " content=" + content);
        verdict("BLOCK", "doBeforeCall", tca.doBeforeCall.get(), rounds);

        assertThat(fired.get()).as("工具应被执行").isTrue();
        assertThat(rounds).as("模型应至少跑 2 轮").isGreaterThanOrEqualTo(2);
        assertThat(tca.doBeforeCall.get())
            .as("doBeforeCall 应为 1 或 == rounds")
            .isIn(1, rounds);
    }
}
