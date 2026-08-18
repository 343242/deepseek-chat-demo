package com.smart.rag.evaluation.testset;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 生成任务进度事件总线（per-job），镜像 {@code EvaluationProgressSink} 的模式：
 * replay().limit(20) 缓存近期事件，订阅晚到可回放；complete 时释放引用。
 */
@Component
public class GenerationProgressSink {

    private static final int REPLAY_LIMIT = 20;

    private final ConcurrentMap<Long, Sinks.Many<GenerationProgressEvent>> sinks =
            new ConcurrentHashMap<>();

    public Sinks.Many<GenerationProgressEvent> getOrCreate(long jobId) {
        return sinks.computeIfAbsent(jobId, k -> Sinks.many().replay().limit(REPLAY_LIMIT));
    }

    public void emit(long jobId, GenerationProgressEvent event) {
        var sink = sinks.get(jobId);
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }

    public void complete(long jobId) {
        var sink = sinks.remove(jobId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /**
     * 订阅进度流（不创建）：sink 缺失 = 任务已结束（complete 时移除）或进程重启后无现场，
     * 返回空 Flux 让桥接立即收尾——避免孤儿 sink 挂住 SSE 到超时。
     * 活任务的 sink 由 {@code GenerationJobService.submit} 预创建，此处无需补建。
     */
    public Flux<GenerationProgressEvent> subscribe(long jobId) {
        var sink = sinks.get(jobId);
        return sink != null ? sink.asFlux() : Flux.empty();
    }
}
