package com.smart.rag.chat.service;

import com.smart.rag.common.util.ConversationIdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 活跃流注册表 — 进程内跟踪每个进行中的 SSE 流式对话，支撑「停止生成」软取消。
 * <p>
 * <b>键设计</b>：{@code isolatedConversationId}（{@code u_{userId}_{rawConversationId}}），
 * userId 内嵌天然实现租户隔离——A 用户的 key 与 B 用户不同，跨用户取消在 registry 层即被拒绝。
 * <p>
 * <b>软取消机制</b>（design §4）：{@link #cancel} 先置 {@code cancelled} 标志（先行于桥接层回调，
 * 消除读序竞态），再 {@code cancelSink.tryEmitEmpty()}（触发 {@code takeUntilOther} 让下游以正常
 * onComplete 终止）。{@code takeUntilOther} 处无缓冲队列，<b>不保证 drain</b>——取消后大概率截断
 * （design §8.1）；已 dispatch 的帧会发完，WebClient 缓冲内未 dispatch 的 chunk 丢弃。
 * <p>
 * <b>单会话单流</b>（design §5.1）：{@link #register} 若同 key 已有旧流，先软取消旧流再替换，
 * 符合「发新消息自动停旧生成」的 UX。
 * <p>
 * <b>兜底清理</b>（design §4.1/P1-4）：僵尸条目（终止回调未触发，如 emitter 建立前连接已死）
 * 由 {@link #cleanupStaleStreams} 定时扫描，<b>走 cancel 路径而非裸 remove</b>——否则 LLM 侧订阅
 * 仍活着、继续烧 token。{@code unregister} 的 CAS 语义防止旧流误删已被替换的新流条目。
 * <p>
 * <b>单实例限制</b>（design §8.3）：registry 是进程内的，多实例部署下取消请求可能落到非发起实例。
 * 首版限单实例。
 */
@Component
public class ActiveStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveStreamRegistry.class);

    /** 与 {@link SseStreamBridge#DEFAULT_TIMEOUT_MS} 对齐 */
    static final long STREAM_TIMEOUT_MS = 300_000L;

    private final ConcurrentHashMap<String, ActiveStream> streams = new ConcurrentHashMap<>();

    /**
     * 单条活跃流。
     * <ul>
     *   <li>{@code cancelSink}：软取消触发器（Sinks.Empty 信号对迟到订阅者可重放，design §4.3）</li>
     *   <li>{@code cancelled}：AtomicBoolean，{@code cancel()} 先行写入（design §4.2，消除读序竞态）</li>
     *   <li>{@code cancelReason}：取消原因（打点 + canceled 帧 data，design §6.1/§4.5）</li>
     *   <li>{@code emitterRef}：AtomicReference，因 register 先于 bridge 创建 emitter，需后填充（design §4.3）</li>
     * </ul>
     */
    public record ActiveStream(
            Sinks.Empty<Void> cancelSink,
            AtomicBoolean cancelled,
            AtomicReference<String> cancelReason,
            AtomicReference<SseEmitter> emitterRef,
            long createdAtMs,
            String userId
    ) {}

    /**
     * 注册活跃流。若同 conversationId 已有旧流，CAS 替换并对旧流软取消（单会话单流，design §5.1）。
     *
     * @return 被替换的旧流（可能为 null）；调用方可用于日志
     */
    public ActiveStream register(String isolatedId, ActiveStream stream) {
        ActiveStream old = streams.put(isolatedId, stream);
        if (old != null) {
            log.info("Replacing active stream for conversation {} (old stream soft-cancelled)",
                    ConversationIdUtil.mask(isolatedId));
            softCancel(old, "SESSION_SWITCH");
        }
        return old;
    }

    /**
     * 软取消：先置 {@code cancelled} 标志（先行于桥接层 complete 回调），再 {@code tryEmitEmpty()}。
     * <p>
     * {@code tryEmitEmpty} 在并发/重复取消时返回 {@code FAIL_NON_SERIALIZED}/{@code FAIL_TERMINATED}——
     * 忽略（design P2-1）：按 key 命中即视为已取消，首次 emit 的信号已被 sink 缓存。
     *
     * @return 是否命中活跃流；{@code false} = 流不存在/已结束（幂等）
     */
    public boolean cancel(String isolatedId, String reason) {
        ActiveStream s = streams.get(isolatedId);
        if (s == null) {
            return false;
        }
        softCancel(s, reason);
        return true;
    }

    private void softCancel(ActiveStream s, String reason) {
        s.cancelled().set(true);                  // ① 先行写标志（design §4.2）
        s.cancelReason().set(reason);             // 记录原因（canceled 帧 data + 打点）
        s.cancelSink().tryEmitEmpty();            // ② 触发 takeUntilOther（EmitResult 失败忽略，P2-1）
    }

    /**
     * 终止时注销（complete/error/timeout 回调）。
     * <p>
     * CAS 语义（{@code remove(key, value)}）：若该 key 已被新流替换（record equals 要求同一实例），
     * 旧流不能误删新流条目（design §5.3）。
     */
    public void unregister(String isolatedId, ActiveStream expected) {
        streams.remove(isolatedId, expected);
    }

    /**
     * 兜底清理：超过 {@link #STREAM_TIMEOUT_MS} 的僵尸条目走 cancel 路径（design §4.1/P1-4）。
     * <p>
     * 僵尸成因是某个终止回调没跑（如 emitter 建立前连接已死），此时 LLM 侧订阅仍活着。
     * 直接对 forEach 持有的 stream 引用做 softCancel（不重新 get），避免误杀刚替换上来的新流。
     * cancel 后由 drain 路径自然 unregister；若条目已不在 map（remove CAS 成功），forEach 拿到的引用
     * 仍可安全 softCancel（对旧流无害）。
     */
    @Scheduled(fixedRate = 60_000L)
    public void cleanupStaleStreams() {
        long now = System.currentTimeMillis();
        streams.forEach((id, s) -> {
            if (now - s.createdAtMs() > STREAM_TIMEOUT_MS) {
                log.warn("Cleaning up stale stream (age={}ms) for conversation {}",
                        now - s.createdAtMs(), ConversationIdUtil.mask(id));
                softCancel(s, "TIMEOUT");
            }
        });
    }

    /** 仅测试用：当前注册条目数 */
    int size() {
        return streams.size();
    }

    /** 仅测试用：按 key 获取（不导出给业务） */
    ActiveStream getIfPresent(String isolatedId) {
        return streams.get(isolatedId);
    }
}
