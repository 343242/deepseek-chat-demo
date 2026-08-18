package com.smart.rag.evaluation.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 评测进度事件总线（per-run）。
 * <p>
 * 每个 run 对应一个 {@link Sinks.Many}，用 {@code replay().limit(20)} 缓存最近 20 条历史事件——
 * 保证 SSE 订阅晚于任务启动时仍能立即收到最近 20 条进度，不会完全丢失早期事件。
 * limit 上界防止内存泄漏（每个 run 最多缓存 20 条小 record）。
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>{@link Sinks.Many} 本身线程安全——{@code tryEmitNext} 可从虚拟线程池任意线程调用</li>
 *   <li>{@link ConcurrentHashMap#computeIfAbsent} 保证 per-run sink 单例创建</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li>{@link #getOrCreate(long)}：run 启动时调用（或在 SSE 订阅时兜底创建）</li>
 *   <li>{@link #emit(long, EvaluationProgressEvent)}：每完成一个 item 调用</li>
 *   <li>{@link #complete(long)}：run 结束（成功/失败/背压拒绝）时调用，发送 onComplete 并从 map 移除释放引用</li>
 * </ul>
 * <p>
 * JVM 崩溃时 entry 可能残留（无 finally 机会），可接受——崩溃是更大问题。
 */
@Component
public class EvaluationProgressSink {

    private static final Logger log = LoggerFactory.getLogger(EvaluationProgressSink.class);

    /** 每个 sink 缓存的历史事件数上限（订阅晚到时回放） */
    private static final int REPLAY_LIMIT = 20;

    private final ConcurrentMap<Long, Sinks.Many<EvaluationProgressEvent>> sinks = new ConcurrentHashMap<>();

    /**
     * 获取或创建某 run 的 sink。幂等——多次调用（run 启动 + SSE 订阅）返回同一实例。
     */
    public Sinks.Many<EvaluationProgressEvent> getOrCreate(long runId) {
        return sinks.computeIfAbsent(runId, k -> Sinks.many().replay().limit(REPLAY_LIMIT));
    }

    /**
     * 推送一条进度事件。sink 不存在时静默丢弃（run 已结束且 sink 已清理）。
     */
    public void emit(long runId, EvaluationProgressEvent event) {
        Sinks.Many<EvaluationProgressEvent> sink = sinks.get(runId);
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }

    /**
     * 结束某 run 的 sink：发送 onComplete 并从 map 移除释放引用。
     * 幂等——重复调用安全。
     */
    public void complete(long runId) {
        Sinks.Many<EvaluationProgressEvent> sink = sinks.remove(runId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /**
     * 以 Flux 形式订阅某 run 的进度流（供 SSE 桥接消费）。
     */
    public Flux<EvaluationProgressEvent> subscribe(long runId) {
        return getOrCreate(runId).asFlux();
    }
}
