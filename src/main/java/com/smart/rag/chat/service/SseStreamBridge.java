package com.smart.rag.chat.service;

import com.smart.rag.chat.dto.FallbackMeta;
import com.smart.rag.chat.dto.CancelReason;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.mode.StreamFrame;
import com.smart.rag.mode.StreamUsageSnapshot;
import com.smart.rag.mode.Reference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE 流式桥接 — 把 Reactor Flux&lt;String&gt; 适配为 SseEmitter。
 * <p>
 * <b>帧协议</b>（与阻塞式 {@code ChatResponse} 字段对齐）：
 * <ol>
 *   <li>content data 帧（默认，逐字 chunk）</li>
 *   <li>{@code event: usage} — 每轮用量（tokenUsage/durationMs；成功完成时必有，厂商未返回 token 时 tokenUsage 为 null）</li>
 *   <li>{@code event: references} — 检索引用（标准模式同步就绪；agent 模式由 doOnComplete 现场构建）</li>
 *   <li>{@code event: agentMetadata} — Agent 元数据（intent/confidence/retrievalRounds）</li>
 *   <li>{@code event: fallback} — 降级信号（最终服务模型 ≠ 用户请求模型）</li>
 *   <li>{@code event: error} — 结构化错误（PRD §3.8：error code + message + attempted）</li>
 * </ol>
 * 收尾帧（usage/references/agentMetadata/fallback）仅在 content 流正常 complete 后发送；
 * error 帧在 onError 时发送（替代收尾帧）。各 holder 为 null 时跳过对应帧，
 * 标准模式（SIMPLE/MULTI_TURN）行为与改动前一致。
 */
@Component
public class SseStreamBridge {

    private static final Logger log = LoggerFactory.getLogger(SseStreamBridge.class);
    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

    /** 活跃流注册表——用于 complete/error/timeout 时注销条目（design §5.3）。
     *  Spring 构造器注入；测试可传 null 跳过注销。 */
    private final @Nullable ActiveStreamRegistry activeStreamRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public SseStreamBridge(@Nullable ActiveStreamRegistry activeStreamRegistry) {
        this.activeStreamRegistry = activeStreamRegistry;
    }

    /** 无参构造（测试用）；registry 为 null 时跳过注销。 */
    public SseStreamBridge() {
        this(null);
    }

    /** 不带收尾帧的桥接（兼容无引用/无 agent 元数据场景） */
    public SseEmitter bridge(Flux<StreamFrame> stream) {
        return bridge(stream, (SseTailFrames) null);
    }

    /** 标准 RAG 桥接：帧流 + references 帧（refsRef 同步就绪） */
    public SseEmitter bridge(Flux<StreamFrame> stream, AtomicReference<List<Reference>> refsRef) {
        return bridge(stream, new SseTailFrames(null, refsRef, null, null, null));
    }

    /**
     * 全量收尾帧桥接：content/reasoning 帧 → references → agentMetadata → fallback。
     * <p>
     * agent 模式的 references 与 agentMetadata.retrievalRounds 由
     * {@code ChatServiceImpl.chatStream} 外层 {@code doOnComplete} 在流结束后刷新
     * （此时 workspace 终值就绪，doOnComplete 副作用先于本类 complete 回调执行）。
     */
    public SseEmitter bridge(Flux<StreamFrame> stream, @Nullable SseTailFrames tail) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        subscribe(emitter, stream, tail);
        return emitter;
    }

    // ==================== 可取消桥接（design chat-stream-cancel.md §4.3/§4.4/§5.3） ====================

    /**
     * 可取消全量桥接：接受外部 cancelSink / cancelled 标志 / cancelReason / emitterRef / isolatedId。
     * <p>
     * 调用方（{@code ChatServiceImpl.chatStream}）<b>必须先 {@code register} 再调本方法</b>，
     * 以保证窗口内的取消信号能被 sink 缓存重放（design §4.3）。emitter 由本方法创建并回填 emitterRef，
     * 供 registry 兜底清理 / canceled 帧使用。订阅终止时按 cancelled 标志分支：true 发 {@code event:canceled}
     * （替代 references/agentMetadata/fallback 收尾帧），false 走正常收尾帧；均 {@code unregister}。
     *
     * @param cancelSink    软取消触发器（{@code takeUntilOther} 用，design §4.4）
     * @param cancelled     AtomicBoolean，{@code registry.cancel()} 先行写入（design §4.2）
     * @param cancelReason  AtomicReference&lt;String&gt;，canceled 帧 data 的 reason 字段
     * @param emitterRef    AtomicReference，本方法创建 emitter 后回填（design §4.3 后填充）
     * @param isolatedId    isolatedConversationId，终止时 registry.unregister 用
     * @param activeStream  对应的 ActiveStream 条目，unregister 的 CAS expected 值
     */
    public SseEmitter bridge(Flux<StreamFrame> stream,
                             @Nullable SseTailFrames tail,
                             Sinks.Empty<Void> cancelSink,
                             AtomicBoolean cancelled,
                             AtomicReference<String> cancelReason,
                             AtomicReference<SseEmitter> emitterRef,
                             String isolatedId,
                             ActiveStreamRegistry.ActiveStream activeStream) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        emitterRef.set(emitter);
        subscribeCancellable(emitter, stream, tail, cancelSink, cancelled, cancelReason,
                isolatedId, activeStream);
        return emitter;
    }

    /** 原始 subscribe（无可取消语义，保留给既有 bridge 重载） */
    void subscribe(SseEmitter emitter, Flux<StreamFrame> stream, @Nullable SseTailFrames tail) {
        AtomicBoolean terminated = new AtomicBoolean(false);
        Disposable subscription = stream.subscribe(
                frame -> sendFrame(emitter, frame, terminated),
                error -> sendError(emitter, error, terminated, tail),
                () -> complete(emitter, terminated, tail)
        );
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            subscription.dispose();
            complete(emitter, terminated, tail);
        });
        emitter.onError(error -> subscription.dispose());
    }

    /**
     * 可取消 subscribe：流被 {@code takeUntilOther(cancelSink)} 包装后，cancelSink 触发时下游以正常
     * onComplete 终止（design §4.4）。complete 回调读 cancelled 标志分支：true 发 canceled 帧，
     * false 走正常收尾帧。所有终止路径均 unregister（CAS 防误删新流，design §5.3）。
     */
    void subscribeCancellable(SseEmitter emitter, Flux<StreamFrame> stream, @Nullable SseTailFrames tail,
                              Sinks.Empty<Void> cancelSink, AtomicBoolean cancelled,
                              AtomicReference<String> cancelReason,
                              String isolatedId,
                              ActiveStreamRegistry.ActiveStream activeStream) {
        AtomicBoolean terminated = new AtomicBoolean(false);
        Disposable subscription = stream.subscribe(
                frame -> sendFrame(emitter, frame, terminated),
                error -> {
                    sendError(emitter, error, terminated, tail);
                    unregisterSafe(isolatedId, activeStream);
                },
                () -> {
                    completeCancellable(emitter, terminated, tail, cancelled, cancelReason);
                    unregisterSafe(isolatedId, activeStream);
                }
        );
        emitter.onCompletion(() -> {
            subscription.dispose();
            unregisterSafe(isolatedId, activeStream);
        });
        emitter.onTimeout(() -> {
            subscription.dispose();
            unregisterSafe(isolatedId, activeStream);
        });
        emitter.onError(error -> {
            subscription.dispose();
            unregisterSafe(isolatedId, activeStream);
        });
    }

    /** CAS 注销：若 key 已被新流替换（record equals 要求同一实例），不误删新流条目（design §5.3） */
    private void unregisterSafe(String isolatedId, ActiveStreamRegistry.ActiveStream activeStream) {
        if (activeStreamRegistry != null && activeStream != null) {
            activeStreamRegistry.unregister(isolatedId, activeStream);
        }
    }

    /**
     * 可取消 complete：读 {@code cancelled} 标志分支（design §4.5）。
     * <ul>
     *   <li>{@code cancelled=true} → 发 {@code event:canceled} 终止帧（替代 references/agentMetadata/fallback）</li>
     *   <li>{@code cancelled=false} → 走正常收尾帧逻辑（与 {@link #complete} 一致）</li>
     * </ul>
     * cancelled 标志由 {@code registry.cancel()} 先行写入（design §4.2），无读序竞态。
     */
    private void completeCancellable(SseEmitter emitter, AtomicBoolean terminated,
                                     @Nullable SseTailFrames tail,
                                     AtomicBoolean cancelled,
                                     AtomicReference<String> cancelReason) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        if (cancelled.get()) {
            sendCanceledFrame(emitter, cancelReason.get());
        } else {
            if (tail != null) {
                sendReferences(emitter, tail.referencesRef());
                sendAgentMetadata(emitter, tail.agentMetadataRef());
                sendFallback(emitter, tail.fallbackRef());
            }
        }
        emitter.complete();
    }

    /** 发送 {@code event:canceled} 终止帧（design §4.5/§6.2 前端权威终止信号） */
    private void sendCanceledFrame(SseEmitter emitter, @Nullable String reason) {
        try {
            synchronized (emitter) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("reason", reason != null ? reason : CancelReason.USER_ABORT.name());
                emitter.send(SseEmitter.event().name("canceled").data(payload));
            }
        } catch (IOException | IllegalStateException e) {
            // 前端可能已关连接；canceled 帧发不出不影响取消本身（上游已 cancel）
            log.debug("Failed to send canceled frame (client likely disconnected): {}", e.getMessage());
        }
    }

    /**
     * 按帧种类分发：CONTENT → 默认 data 帧（逐字正文）；REASONING → {@code event:reasoning} 帧
     * （思考过程）；RESET → {@code event:reset} 独立命名事件（模型切换，前端清空已累积缓冲，
     * design llm-resilience-optimization WS5）。
     */
    private void sendFrame(SseEmitter emitter, StreamFrame frame, AtomicBoolean terminated) {
        try {
            synchronized (emitter) {
                if (terminated.get()) {
                    return;
                }
                SseEmitter.SseEventBuilder event;
                if (frame.isReasoning()) {
                    event = SseEmitter.event().name("reasoning").data(frame.payload());
                } else if (frame.isReset()) {
                    event = SseEmitter.event().name("reset").data(frame.payload());
                } else {
                    event = SseEmitter.event().data(frame.payload());
                }
                emitter.send(event);
            }
        } catch (IOException | IllegalStateException e) {
            terminated.set(true);
            emitter.completeWithError(e);
        }
    }

    private void sendError(SseEmitter emitter, Throwable error, AtomicBoolean terminated,
                           @Nullable SseTailFrames tail) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(buildErrorPayload(error, tail)));
            }
            emitter.complete();
        } catch (IOException | IllegalStateException sendError) {
            emitter.completeWithError(sendError);
        }
        log.warn("SSE stream failed: {}", error.getMessage());
    }

    /** PRD §3.8 结构化 error 帧：error code + message + attempted（降级链耗尽时携带已尝试模型） */
    private Map<String, Object> buildErrorPayload(Throwable error, @Nullable SseTailFrames tail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", errorCode(error));
        payload.put("message", userMessage(error));
        if (tail != null && tail.attemptedModels() != null && !tail.attemptedModels().isEmpty()) {
            payload.put("attempted", tail.attemptedModels());
        }
        return payload;
    }

    private static String errorCode(Throwable error) {
        if (error instanceof RemoteException re && re.getErrorCode() instanceof Enum<?> e) {
            return e.name();
        }
        return "stream_error";
    }

    private static String userMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : "流式响应失败，请稍后重试";
    }

    private void complete(SseEmitter emitter, AtomicBoolean terminated, @Nullable SseTailFrames tail) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        if (tail != null) {
            sendUsage(emitter, tail.usageRef());
            sendReferences(emitter, tail.referencesRef());
            sendAgentMetadata(emitter, tail.agentMetadataRef());
            sendFallback(emitter, tail.fallbackRef());
        }
        emitter.complete();
    }

    /** 每轮用量尾帧（tokenUsage/durationMs）；usageRef 为 null 或快照未写（错误/取消流）时跳过 */
    private void sendUsage(SseEmitter emitter, @Nullable AtomicReference<StreamUsageSnapshot> usageRef) {
        if (usageRef == null) {
            return;
        }
        StreamUsageSnapshot usage = usageRef.get();
        if (usage == null) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("usage").data(usage));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send usage frame: {}", e.getMessage());
        }
    }

    /** 内容流末尾追加 references 帧；refsRef 为 null 或 references 为空时跳过 */
    private void sendReferences(SseEmitter emitter, @Nullable AtomicReference<List<Reference>> refsRef) {
        if (refsRef == null) {
            return;
        }
        List<Reference> refs = refsRef.get();
        if (refs == null || refs.isEmpty()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("references").data(refs));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send references frame: {}", e.getMessage());
        }
    }

    /** Agent 元数据帧（intent/confidence/retrievalRounds）；metaRef 为 null 或空时跳过 */
    private void sendAgentMetadata(SseEmitter emitter,
                                    @Nullable AtomicReference<Map<String, Object>> metaRef) {
        if (metaRef == null) {
            return;
        }
        Map<String, Object> meta = metaRef.get();
        if (meta == null || meta.isEmpty()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("agentMetadata").data(meta));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send agentMetadata frame: {}", e.getMessage());
        }
    }

    /** 降级信号帧；fallbackRef 为 null 或未发生降级时跳过 */
    private void sendFallback(SseEmitter emitter, @Nullable AtomicReference<FallbackMeta> fallbackRef) {
        if (fallbackRef == null) {
            return;
        }
        FallbackMeta meta = fallbackRef.get();
        if (meta == null || !meta.fallback()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("fallback").data(meta));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send fallback frame: {}", e.getMessage());
        }
    }

    /**
     * SSE 收尾帧数据源聚合（content 流结束后发送，与阻塞式 {@code ChatResponse} 字段对齐）。
     * <p>
     * 各 holder 为 null 时跳过对应帧：
     * <ul>
     *   <li>{@code usageRef}：策略层 doOnComplete 写入的每轮用量快照（成功流必有）</li>
     *   <li>{@code referencesRef}：标准模式同步就绪；agent 模式由 doOnComplete 刷新</li>
     *   <li>{@code agentMetadataRef}：Agent 模式 intent/confidence 订阅前就绪，retrievalRounds 流后刷新</li>
     *   <li>{@code fallbackRef}：跨模型降级时由 chatStream 捕获</li>
     *   <li>{@code attemptedModels}：降级链已尝试模型，仅 error 帧读取</li>
     * </ul>
     */
    public record SseTailFrames(
            @Nullable AtomicReference<StreamUsageSnapshot> usageRef,
            @Nullable AtomicReference<List<Reference>> referencesRef,
            @Nullable AtomicReference<Map<String, Object>> agentMetadataRef,
            @Nullable AtomicReference<FallbackMeta> fallbackRef,
            @Nullable List<String> attemptedModels
    ) {}
}
