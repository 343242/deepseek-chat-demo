package com.smart.rag.rag.sse;

import com.smart.rag.rag.event.DocumentStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档状态 SSE 连接注册表（进程内）。
 * <p>
 * 按 {@code userId} 索引当前实例持有的 {@link SseEmitter}，支持同一用户多 tab（值为 Set）。
 * 跨实例广播由 {@link DocumentSseRelay}（Redis Pub/Sub）负责——任意实例收到
 * {@link DocumentStatusChangedEvent} 后 publish 到 Redis Topic，所有实例的 listener
 * 收到后调本类的 {@link #send} 查本地连接转发。
 *
 * <h3>线程安全</h3>
 * {@link SseEmitter#send} 非线程安全（见 {@code EvaluationSseBridge} 的先例注释）。
 * {@link #send} 和 {@link #heartbeat} 均 {@code synchronized(emitter)} 守卫并发串行化；
 * 客户端断开（IOException/IllegalStateException）→ 从 registry 移除，避免泄漏。
 *
 * <h3>心跳</h3>
 * 5s 间隔发 SSE 注释帧（{@code :hb}），防 nginx/中间代理 idle 超时断连。
 * 注释帧不触发前端 {@code onmessage}，EventSource 原生忽略。
 */
@Component
public class DocumentSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentSseRegistry.class);

    /** userId → 该用户在本实例的所有 SSE 连接（多 tab） */
    private final ConcurrentHashMap<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 注册 SSE 连接。emitter 终止（complete/timeout/error）时自动从 registry 移除。
     */
    public void register(Long userId, SseEmitter emitter) {
        emitters.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
    }

    /**
     * 向指定用户的所有本地连接推送状态变更。无连接时直接返回（跨实例下大多数实例会走此路径）。
     */
    public void send(DocumentStatusChangedEvent event) {
        Set<SseEmitter> set = emitters.get(event.userId());
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            sendToEmitter(event.userId(), emitter, SseEmitter.event().name("status").data(event));
        }
    }

    /**
     * 5s 心跳：遍历所有连接发注释帧。
     */
    @Scheduled(fixedRate = 5000)
    public void heartbeat() {
        emitters.forEach((userId, set) ->
                set.forEach(emitter -> sendToEmitter(userId, emitter, SseEmitter.event().comment("hb"))));
    }

    private void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.get(userId);
        if (set != null) {
            set.remove(emitter);
            if (set.isEmpty()) {
                emitters.remove(userId, set);
            }
        }
    }

    /**
     * 线程安全发送：synchronized 守卫 + 断开自动清理。
     */
    private void sendToEmitter(Long userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            synchronized (emitter) {
                emitter.send(event);
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE client disconnected (doc-status): userId={}, {}", userId, e.getMessage());
            remove(userId, emitter);
        }
    }

    /** 仅测试用：当前有活跃 SSE 连接的用户数 */
    int userCount() {
        return emitters.size();
    }
}
