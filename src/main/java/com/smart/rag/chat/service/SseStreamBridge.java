package com.smart.rag.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SseStreamBridge {

    private static final Logger log = LoggerFactory.getLogger(SseStreamBridge.class);
    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

    public SseEmitter bridge(Flux<String> stream) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        subscribe(emitter, stream);
        return emitter;
    }

    void subscribe(SseEmitter emitter, Flux<String> stream) {
        AtomicBoolean terminated = new AtomicBoolean(false);
        Disposable subscription = stream.subscribe(
                chunk -> sendChunk(emitter, chunk, terminated),
                error -> sendError(emitter, error, terminated),
                () -> complete(emitter, terminated)
        );
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            subscription.dispose();
            complete(emitter, terminated);
        });
        emitter.onError(error -> subscription.dispose());
    }

    private void sendChunk(SseEmitter emitter, String chunk, AtomicBoolean terminated) {
        if (terminated.get()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().data(chunk));
            }
        } catch (IOException | IllegalStateException e) {
            terminated.set(true);
            emitter.completeWithError(e);
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
                        .data("流式响应失败，请稍后重试"));
            }
            emitter.complete();
        } catch (IOException | IllegalStateException sendError) {
            emitter.completeWithError(sendError);
        }
        log.warn("SSE stream failed: {}", error.getMessage());
    }

    private void complete(SseEmitter emitter, AtomicBoolean terminated) {
        if (terminated.compareAndSet(false, true)) {
            emitter.complete();
        }
    }
}
