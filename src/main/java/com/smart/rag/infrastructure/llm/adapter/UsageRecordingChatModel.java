package com.smart.rag.infrastructure.llm.adapter;

import com.smart.rag.infrastructure.llm.usage.UsageContext;
import com.smart.rag.infrastructure.llm.usage.UsageEventSink;
import com.smart.rag.infrastructure.llm.usage.UsageSample;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用量采集装饰器 — 全部 LLM 调用的统一采集咽喉（阻塞/流式/CHAT/AGENT/INTENT 同一份逻辑）。
 * <p>
 * 一次 {@link #call(Prompt)} / {@link #stream(Prompt)} = 一条用量事件：
 * <ul>
 *   <li>计时从调用发起到终态（含失败/取消），失败也采样（success=false，token 未知为 null）</li>
 *   <li>真实 usage 取自响应 metadata（{@code ChatModelAdapter} 在轮末汇总包注入）；
 *       多轮工具调用在同一 stream 内求和后发布一次</li>
 *   <li>真实 usage 缺失时按字符数/4 估算（blocking/streaming 同语义），置 estimated=true</li>
 *   <li>发布经 {@link UsageEventSink} 异步化，绝不向主链路抛出</li>
 * </ul>
 * 同时暴露累计 token 读取器，供 AgentGuardrails 在 ReAct 轮间做上限检查
 * （合并自原 TokenCountingChatModel——护栏与落库消费同一实例，两套机制合一）。
 * <p>
 * 此类是请求级对象（经 {@code ChatModelAssembler} 每请求构造），不是 Spring Bean；
 * 实例字段（累计值）仅被同一请求的调用链串行访问，无跨请求共享。
 */
public class UsageRecordingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordingChatModel.class);

    /** 字符估算比例：4 个字符约 1 token */
    private static final int CHARS_PER_TOKEN = 4;

    private final ChatModel delegate;
    private final UsageContext context;
    private final UsageEventSink sink;

    private long totalPromptTokens = 0;
    private long totalCompletionTokens = 0;
    private boolean estimationUsed = false;

    public UsageRecordingChatModel(ChatModel delegate, UsageContext context, UsageEventSink sink) {
        this.delegate = delegate;
        this.context = context;
        this.sink = sink;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long startNanos = System.nanoTime();
        try {
            ChatResponse response = delegate.call(prompt);
            Usage usage = realUsage(response);
            long promptTokens;
            long completionTokens;
            boolean estimated;
            if (usage != null) {
                promptTokens = usage.getPromptTokens();
                completionTokens = nullSafe(usage.getCompletionTokens());
                estimated = false;
            } else {
                promptTokens = estimateTokens(promptChars(prompt));
                completionTokens = estimateTokens(responseChars(response));
                estimated = true;
                estimationUsed = true;
            }
            totalPromptTokens += promptTokens;
            totalCompletionTokens += completionTokens;
            publish(promptTokens, completionTokens, estimated, true, elapsedMs(startNanos));
            return response;
        } catch (RuntimeException e) {
            publish(null, null, false, false, elapsedMs(startNanos));
            throw e;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            long startNanos = System.nanoTime();
            long promptCharCount = promptChars(prompt);
            // 本 stream 的 per-call 累计（与实例级跨轮累计分离：Agent 一个实例跑多轮 ReAct）
            AtomicLong callPromptTokens = new AtomicLong();
            AtomicLong callCompletionTokens = new AtomicLong();
            AtomicLong outputChars = new AtomicLong();
            AtomicBoolean realUsageSeen = new AtomicBoolean(false);

            return delegate.stream(prompt)
                .doOnNext(response -> {
                    Usage usage = realUsage(response);
                    if (usage != null) {
                        // 仅轮末汇总包带真实 usage；中间 chunk 跳过，避免片段重复累加
                        realUsageSeen.set(true);
                        long p = usage.getPromptTokens();
                        long c = nullSafe(usage.getCompletionTokens());
                        callPromptTokens.addAndGet(p);
                        callCompletionTokens.addAndGet(c);
                        totalPromptTokens += p;
                        totalCompletionTokens += c;
                    } else {
                        outputChars.addAndGet(responseChars(response));
                    }
                })
                .doFinally(signal -> publishStreamResult(signal, startNanos, promptCharCount,
                    callPromptTokens, callCompletionTokens, outputChars, realUsageSeen));
        });
    }

    private void publishStreamResult(SignalType signal,
                                     long startNanos,
                                     long promptCharCount,
                                     AtomicLong callPromptTokens,
                                     AtomicLong callCompletionTokens,
                                     AtomicLong outputChars,
                                     AtomicBoolean realUsageSeen) {
        long elapsed = elapsedMs(startNanos);
        if (realUsageSeen.get()) {
            publish(callPromptTokens.get(), callCompletionTokens.get(), false,
                signal == SignalType.ON_COMPLETE, elapsed);
            return;
        }
        if (signal == SignalType.ON_ERROR) {
            // 错误终态：无法估算（输出不完整），token 记未知
            publish(null, null, false, false, elapsed);
            return;
        }
        // COMPLETE/CANCEL 且厂商未返回 usage：字符估算兜底（与阻塞路径同语义）
        long estimatedPrompt = estimateTokens(promptCharCount);
        long estimatedCompletion = estimateTokens(outputChars.get());
        totalPromptTokens += estimatedPrompt;
        totalCompletionTokens += estimatedCompletion;
        estimationUsed = true;
        publish(estimatedPrompt, estimatedCompletion, true, signal == SignalType.ON_COMPLETE, elapsed);
    }

    private void publish(@Nullable Long promptTokens,
                         @Nullable Long completionTokens,
                         boolean estimated,
                         boolean success,
                         long durationMs) {
        sink.accept(new UsageSample(context, promptTokens, completionTokens, estimated, success, durationMs));
        log.debug("Usage sampled: scene={}, candidate={}, prompt={}, completion={}, estimated={}, success={}, duration={}ms",
            context.scene(), context.candidateId(), promptTokens, completionTokens, estimated, success, durationMs);
    }

    /** 提取真实 usage；{@code ChatResponseMetadata} 默认 {@code EmptyUsage}（promptTokens=0）视为无。 */
    @Nullable
    private static Usage realUsage(@Nullable ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        return usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0
            ? usage : null;
    }

    private static long nullSafe(@org.jspecify.annotations.Nullable Integer value) {
        return value != null ? value : 0;
    }

    private static long promptChars(Prompt prompt) {
        long chars = 0;
        for (Message message : prompt.getInstructions()) {
            if (message.getText() != null) {
                chars += message.getText().length();
            }
        }
        return chars;
    }

    private static long responseChars(@Nullable ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return 0;
        }
        String text = response.getResult().getOutput().getText();
        return text != null ? text.length() : 0;
    }

    private static long estimateTokens(long chars) {
        return chars / CHARS_PER_TOKEN;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    // === 跨轮累计读取器（AgentGuardrails 轮间上限检查消费） ===

    public long getTotalTokens() {
        return totalPromptTokens + totalCompletionTokens;
    }

    public long getTotalPromptTokens() {
        return totalPromptTokens;
    }

    public long getTotalCompletionTokens() {
        return totalCompletionTokens;
    }

    public boolean isEstimationUsed() {
        return estimationUsed;
    }
}
