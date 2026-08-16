package com.smart.rag.rag.sse;

import com.smart.rag.rag.etl.EtlStatus;
import com.smart.rag.rag.event.DocumentStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.EnumSet;
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
 * <h3>连接生命周期</h3>
 * 流不常驻：事件按 owner 路由，前端只在「自己在途文档存在」时订阅（见前端 knowledge-page）。
 * 服务端对应地做<strong>在途感知的空闲收尾</strong>——该用户无在途文档且超过宽限期
 * （{@code app.sse.document-idle-grace-ms}，默认 1 分钟）没有事件，就主动 complete 其本地连接，
 * 而非吊满 10 分钟 emitter 超时、依赖前端 EventSource 无限重连。在途判定从 status 事件推导：
 * ETL 单阶段（解析→入库）间隔可超过宽限期，但只要文档仍处非终态即豁免收尾，阶段间隔无关紧要。
 * 收尾 complete 触发的 ASYNC dispatch 由 {@code JwtAuthenticationFilter} 的 request 级认证快照兜底。
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

    /** ETL 在途状态——出现即视为该用户有活跃处理，空闲收尾豁免（PENDING_APPROVAL 等不产生事件的状态不在此列） */
    private static final Set<EtlStatus> IN_FLIGHT_STATUSES = EnumSet.of(
        EtlStatus.UPLOADED, EtlStatus.PARSING, EtlStatus.CHUNKING, EtlStatus.VECTORIZING, EtlStatus.PROCESSING);

    /** userId → 该用户在本实例的所有 SSE 连接（多 tab） */
    private final ConcurrentHashMap<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** userId → 在途文档 id 集合。从 status 事件推导；事件经 relay 到达所有实例，与本地是否持有连接无关 */
    private final ConcurrentHashMap<Long, Set<Long>> inFlightDocs = new ConcurrentHashMap<>();

    /** userId → 最后一次 status 事件时间戳，空闲收尾的计时基准 */
    private final ConcurrentHashMap<Long, Long> lastEventAt = new ConcurrentHashMap<>();

    /** 空闲收尾宽限期：该用户无在途文档且持续无事件超过此时长 → complete 其本地连接 */
    private final long idleGraceMs;

    public DocumentSseRegistry(
        @Value("${app.sse.document-idle-grace-ms:60000}") long idleGraceMs) {
        this.idleGraceMs = idleGraceMs;
    }

    /**
     * 注册 SSE 连接。emitter 终止（complete/timeout/error）时自动从 registry 移除。
     */
    public void register(Long userId, SseEmitter emitter) {
        emitters.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        // 新连接从注册时刻起算宽限（computeIfAbsent 不覆盖已有计时），保证首事件到达前不被收尾
        lastEventAt.computeIfAbsent(userId, k -> System.currentTimeMillis());
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));
    }

    /**
     * 向指定用户的所有本地连接推送状态变更。无连接时直接返回（跨实例下大多数实例会走此路径）。
     * 在途追踪先于路由执行——无论本实例是否持有该用户连接，都要维护在途/计时状态。
     */
    public void send(DocumentStatusChangedEvent event) {
        trackInFlight(event);
        Set<SseEmitter> set = emitters.get(event.userId());
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : set) {
            sendToEmitter(event.userId(), emitter, SseEmitter.event().name("status").data(event));
        }
    }

    /**
     * 从事件推导在途集合：在途状态加入，其余状态（COMPLETED/FAILED/VECTOR_FAILED/REJECTED/SUPERSEDED/
     * PENDING_APPROVAL）移除。
     */
    private void trackInFlight(DocumentStatusChangedEvent event) {
        Long userId = event.userId();
        lastEventAt.put(userId, System.currentTimeMillis());
        if (IN_FLIGHT_STATUSES.contains(event.status())) {
            inFlightDocs.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(event.documentId());
        } else {
            Set<Long> docs = inFlightDocs.get(userId);
            if (docs != null) {
                docs.remove(event.documentId());
                if (docs.isEmpty()) {
                    inFlightDocs.remove(userId, docs);
                }
            }
        }
    }

    /**
     * 5s 心跳：遍历所有连接发注释帧；同时对「无在途文档且超过宽限期无事件」的用户
     * 主动 complete 本地连接（见类注释「连接生命周期」）。
     */
    @Scheduled(fixedRate = 5000)
    public void heartbeat() {
        long now = System.currentTimeMillis();
        emitters.forEach((userId, set) -> {
            boolean idle = inFlightDocs.get(userId) == null
                && now - lastEventAt.getOrDefault(userId, now) >= idleGraceMs;
            set.forEach(emitter -> {
                if (idle) {
                    completeQuietly(userId, emitter);
                } else {
                    sendToEmitter(userId, emitter, SseEmitter.event().comment("hb"));
                }
            });
        });
    }

    private void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> set = emitters.get(userId);
        if (set != null) {
            set.remove(emitter);
            if (set.isEmpty()) {
                emitters.remove(userId, set);
                // 连接清空时一并清理计时；重连由 register 重新起算宽限
                lastEventAt.remove(userId);
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

    /**
     * 空闲收尾：complete 失败（连接已断等）视同清理路径处理。
     */
    private void completeQuietly(Long userId, SseEmitter emitter) {
        try {
            synchronized (emitter) {
                emitter.complete();
            }
        } catch (Exception e) {
            log.debug("SSE complete failed (doc-status): userId={}, {}", userId, e.getMessage());
            remove(userId, emitter);
        }
    }

    /** 仅测试用：当前有活跃 SSE 连接的用户数 */
    int userCount() {
        return emitters.size();
    }
}
