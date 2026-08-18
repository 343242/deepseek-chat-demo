package com.smart.rag.evaluation.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 评测进度 SSE 桥接。
 * <p>
 * 把 {@code EvaluationProgressSink} 的 {@code Flux<EvaluationProgressEvent>} 适配为 Spring MVC 的 {@link SseEmitter}。
 * 结构对齐 {@code chat/service/SseStreamBridge}（项目唯一的 SSE 桥接先例），但事件类型与帧语义不同——
 * 评测推送的是 per-item 进度，而非 LLM token 流。
 *
 * <h3>线程安全</h3>
 * {@code SseEmitter.send()} 非线程安全。事件来自虚拟线程（{@code EvaluationExecutionService} 的执行线程），
 * 桥接侧用 {@code synchronized(emitter)} + {@code AtomicBoolean terminated} 守卫，复刻 SseStreamBridge 的模式：
 * <ul>
 *   <li>{@code terminated} CAS 保证 complete/error 帧只发一次</li>
 *   <li>{@code synchronized} 保证并发 send 串行化</li>
 *   <li>客户端断开（IOException/IllegalStateException）→ {@code completeWithError} + 标记 terminated</li>
 * </ul>
 *
 * <h3>帧类型</h3>
 * <ul>
 *   <li>{@code event: progress} —— 每条 {@link EvaluationProgressEvent}</li>
 *   <li>{@code event: done} —— Flux onComplete 时发送（携带最终 summary）</li>
 *   <li>{@code event: error} —— Flux onError 时发送</li>
 * </ul>
 *
 * <h3>超时</h3>
 * 实例级长超时 1 小时（{@link #SSE_TIMEOUT_MS}），覆盖全局 5 分钟（{@code WebMvcConfig}）。
 * 评测可能跑数十分钟，全局 5 分钟会过早断开。超时后客户端可重连——sink 还在，replay 历史继续。
 */
@Component
public class EvaluationSseBridge {

    private static final Logger log = LoggerFactory.getLogger(EvaluationSseBridge.class);

    /** SSE 连接超时：1 小时（覆盖全局 5 分钟；评测长任务用） */
    static final long SSE_TIMEOUT_MS = 3_600_000L;

    /**
     * 把进度 Flux 桥接为 SseEmitter。
     *
     * @param progressFlux 来自 {@code EvaluationProgressSink.subscribe(runId)} 的进度流
     * @return 配置好订阅与生命周期的 SseEmitter
     */
    public SseEmitter bridge(Flux<EvaluationProgressEvent> progressFlux) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean terminated = new AtomicBoolean(false);

        Disposable subscription = progressFlux.subscribe(
                event -> sendProgress(emitter, event, terminated),
                error -> sendError(emitter, error, terminated),
                () -> complete(emitter, terminated)
        );

        // 客户端断开 / 超时 / 异常时释放 Reactor 订阅，防止后台 Flux 泄漏
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(error -> subscription.dispose());

        return emitter;
    }

    /**
     * 已结束 run 的终态直连（镜像 testset 的 GenerationSseBridge.bridgeTerminated）：
     * run 终态时 sink 已 complete 并移除，subscribe 会兜底创建永不 complete 的 sink
     * （entry 泄漏 + 连接挂到超时），因此不订阅、直接回放终态事件并立即收尾。
     */
    public SseEmitter bridgeTerminated(EvaluationRun run) {
        SseEmitter emitter = new SseEmitter(0L); // 立即完成，无需超时
        AtomicBoolean terminated = new AtomicBoolean(false);
        boolean failed = run.status() == EvaluationRunStatus.FAILED;
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(failed ? "error" : "done")
                        .data(Map.of(
                                "runId", run.id(),
                                "status", run.status().getValue(),
                                "message", failed ? "评测运行已结束（失败）" : "评测运行已结束")));
            }
            if (failed) {
                emitter.completeWithError(new IllegalStateException("评测运行已结束（失败）"));
            } else {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void sendProgress(SseEmitter emitter, EvaluationProgressEvent event, AtomicBoolean terminated) {
        try {
            synchronized (emitter) {
                if (terminated.get()) {
                    return;
                }
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(event));
            }
        } catch (IOException | IllegalStateException e) {
            terminated.set(true);
            emitter.completeWithError(e);
            log.debug("SSE client disconnected (progress): {}", e.getMessage());
        }
    }

    private void sendError(SseEmitter emitter, Throwable error, AtomicBoolean terminated) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("message", "评测进度流异常: " + error.getMessage())));
            }
            emitter.complete();
        } catch (IOException | IllegalStateException sendError) {
            emitter.completeWithError(sendError);
        }
        log.warn("SSE progress stream failed: {}", error.getMessage());
    }

    private void complete(SseEmitter emitter, AtomicBoolean terminated) {
        if (terminated.compareAndSet(false, true)) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data(Map.of("message", "评测运行结束")));
                }
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
                log.debug("SSE client disconnected (done): {}", e.getMessage());
            }
        }
    }
}
