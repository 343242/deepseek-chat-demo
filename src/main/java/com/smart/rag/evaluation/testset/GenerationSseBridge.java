package com.smart.rag.evaluation.testset;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生成进度 Flux → SseEmitter 桥接（镜像 {@code EvaluationSseBridge}：
 * synchronized(emitter) + 单次终止标记 + 1 小时超时）。
 */
@Component
@Profile("evaluation")
public class GenerationSseBridge {

    private static final long SSE_TIMEOUT_MILLIS = 60 * 60 * 1000L;

    public SseEmitter bridge(long jobId, GenerationProgressSink sink) {
        var emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        var terminated = new AtomicBoolean(false);

        sink.subscribe(jobId).subscribe(
                event -> send(emitter, terminated, event),
                error -> terminate(emitter, terminated, error),
                () -> terminate(emitter, terminated, null));

        emitter.onTimeout(() -> terminate(emitter, terminated, null));
        emitter.onError(e -> terminated.set(true));
        return emitter;
    }

    private void send(SseEmitter emitter, AtomicBoolean terminated,
                      GenerationProgressEvent event) {
        if (terminated.get()) {
            return;
        }
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                terminate(emitter, terminated, e);
            }
        }
    }

    private void terminate(SseEmitter emitter, AtomicBoolean terminated, Throwable error) {
        if (terminated.compareAndSet(false, true)) {
            synchronized (emitter) {
                try {
                    if (error == null) {
                        emitter.send(SseEmitter.event().name("done").data("{}"));
                        emitter.complete();
                    } else {
                        emitter.completeWithError(error);
                    }
                } catch (IOException ignored) {
                    emitter.completeWithError(error);
                }
            }
        }
    }
}
