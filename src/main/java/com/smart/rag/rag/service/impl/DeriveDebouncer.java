package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * derive 防抖（V30 §3.6，批量上传对策）。
 * <p>
 * 批量 N 文档 → N 次 {@code graphChanged=true} → N 次 derive（O(scope) 计算 ×N 的 CPU 放大）。
 * 改为 scope 级 trailing 合并：写路径 graphChanged 后不立即 derive，而是标记 scope 待算并调度
 * 延迟窗口（{@code derive-debounce-millis}，默认 30s；0 = 关闭回退逐文档即时 derive）内的
 * 后续写入合并为<b>一次</b> derive——固定窗口自首次提交起算（连续写入不会无限顺延），
 * 窗口末尾执行，覆盖窗口内全部拓扑变化。
 * <p>
 * 取舍：结构分就绪延迟常态 ≤ 窗口（检索由默认分兜底——derive 前用默认分本就是现状语义）；
 * 异常态例外：防抖任务在内存调度，进程重启即丢——若重启后该 scope 无新写入，结构分最坏陈旧至
 * 周一 forceDerive（≤7 天）；期间 {@code community_stale=TRUE} 可作观测信号。
 * 多实例下每实例各自防抖，跨实例重复 derive 幂等无害。
 */
@Component
public class DeriveDebouncer {

    private static final Logger log = LoggerFactory.getLogger(DeriveDebouncer.class);

    private final CommunityDetectionJob communityDetectionJob;
    private final RagEntityProperties properties;

    /** 独立单线程调度器（具名 daemon 线程）：防抖任务轻量，单线程足够且不占共享调度线程。 */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "derive-debouncer");
                t.setDaemon(true);
                return t;
            });

    /** scope → 待算任务（pending 存在即窗口已调度；fire 时移除）。 */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public DeriveDebouncer(CommunityDetectionJob communityDetectionJob,
                           RagEntityProperties properties) {
        this.communityDetectionJob = communityDetectionJob;
        this.properties = properties;
    }

    /**
     * 提交 scope 待 derive。窗口内重复提交合并为窗口末尾的一次执行。
     */
    public void submit(Long userId, @Nullable Long teamId) {
        long windowMillis = properties.deriveDebounceMillis();
        if (windowMillis <= 0) {
            runDerived(userId, teamId);   // 0 = 关闭防抖，回退逐文档即时 derive（现状语义）
            return;
        }
        String key = scopeKey(userId, teamId);
        pending.computeIfAbsent(key, k ->
                scheduler.schedule(() -> {
                    pending.remove(key);
                    runDerived(userId, teamId);
                }, windowMillis, TimeUnit.MILLISECONDS));
    }

    private void runDerived(Long userId, @Nullable Long teamId) {
        try {
            communityDetectionJob.run(userId, teamId);
        } catch (Exception e) {
            // failure-isolated：derive 失败不影响 Path A/B，下次写入或周一 forceDerive 兜底
            log.error("Debounced derive failed for userId={}, teamId={}: {}",
                    userId, teamId, e.getMessage(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static String scopeKey(Long userId, @Nullable Long teamId) {
        return userId + ":" + (teamId != null ? teamId : -1L);
    }
}
