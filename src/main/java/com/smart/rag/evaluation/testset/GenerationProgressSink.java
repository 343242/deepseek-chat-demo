package com.smart.rag.evaluation.testset;

import org.springframework.context.annotation.Profile;
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
@Profile("evaluation")
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

    public Flux<GenerationProgressEvent> subscribe(long jobId) {
        return getOrCreate(jobId).asFlux();
    }
}
