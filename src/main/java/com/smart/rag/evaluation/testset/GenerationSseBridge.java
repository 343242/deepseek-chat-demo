package com.smart.rag.evaluation.testset;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生成进度 Flux → SseEmitter 桥接（镜像 {@code EvaluationSseBridge}：
 * synchronized(emitter) + 单次终止标记 + 1 小时超时）。
 */
@Component
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

    /**
     * 已结束任务的终态直连：不订阅（sink 已 complete 并移除，订阅会挂到超时），
     * 直接回放最后一条进度并以 done/error 收尾——晚到的订阅者立即拿到闭环。
     */
    public SseEmitter bridgeTerminated(GenerationJobRecord job) {
        var emitter = new SseEmitter(0L); // 立即完成，无需超时
        var terminated = new AtomicBoolean(false);
        var finalEvent = new GenerationProgressEvent(
                "done", 1, 1,
                "completed".equals(job.status())
                        ? "生成已完成（datasetId=" + job.datasetId() + "）"
                        : "生成失败：" + job.error());
        send(emitter, terminated, finalEvent);
        if ("completed".equals(job.status())) {
            terminate(emitter, terminated, null);
        } else {
            terminate(emitter, terminated,
                    new IllegalStateException(job.error() == null ? "生成失败" : job.error()));
        }
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
