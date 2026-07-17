package com.smart.rag.chat.service;

import com.smart.rag.mode.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SseStreamBridge {

    private static final Logger log = LoggerFactory.getLogger(SseStreamBridge.class);
    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

    /** 不带 references 的桥接（兼容非 RAG / 无引用场景） */
    public SseEmitter bridge(Flux<String> stream) {
        return bridge(stream, null);
    }

    /**
     * 带 references 的桥接（R8）：内容流正常下发，complete 后追加 {@code event: references} 帧
     * （取最终成功模型的 references，由 chatStream 的 AtomicReference 捕获）。
     *
     * @param stream  内容流（已走 advisor 链 + fallbackExecutor 跨模型降级）
     * @param refsRef references 捕获器（null 表示不发 references 帧）
     */
    public SseEmitter bridge(Flux<String> stream, AtomicReference<List<Reference>> refsRef) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        subscribe(emitter, stream, refsRef);
        return emitter;
    }

    void subscribe(SseEmitter emitter, Flux<String> stream, AtomicReference<List<Reference>> refsRef) {
        AtomicBoolean terminated = new AtomicBoolean(false);
        Disposable subscription = stream.subscribe(
                chunk -> sendChunk(emitter, chunk, terminated),
                error -> sendError(emitter, error, terminated),
                () -> complete(emitter, terminated, refsRef)
        );
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            subscription.dispose();
            complete(emitter, terminated, refsRef);
        });
        emitter.onError(error -> subscription.dispose());
    }

    private void sendChunk(SseEmitter emitter, String chunk, AtomicBoolean terminated) {
        try {
            synchronized (emitter) {
                if (terminated.get()) {
                    return;
                }
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

    private void complete(SseEmitter emitter, AtomicBoolean terminated,
                          AtomicReference<List<Reference>> refsRef) {
        if (terminated.compareAndSet(false, true)) {
            sendReferences(emitter, refsRef);
            emitter.complete();
        }
    }

    /** 内容流末尾追加 references 帧（R8）；refsRef 为 null 或 references 为空时跳过 */
    private void sendReferences(SseEmitter emitter, AtomicReference<List<Reference>> refsRef) {
        if (refsRef == null) {
            return;
        }
        List<Reference> refs = refsRef.get();
        if (refs == null || refs.isEmpty()) {
            return;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name("references")
                        .data(refs));
            }
        } catch (IOException | IllegalStateException e) {
            // references 帧失败不影响已下发的内容，仅记录
            log.warn("Failed to send references frame: {}", e.getMessage());
        }
    }
}
