package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.StreamInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主 stream 物理裁剪任务（P1-5，design §2）——固定 {@code MAXLEN ~} 会在消费积压时物理删除
 * <b>尚未进入 PEL 的 entry</b>（XREADGROUP {@code >} 只投递从未投给 group 的消息；trim 删掉的
 * 从未投递消息无人补偿）。改为按 {@code XINFO GROUPS} 各组 {@code last-delivered-id} 的
 * <b>最小值</b> 做 {@code XTRIM MINID ~}：只裁剪"各组都已读过"的区间，积压不丢未投递消息。
 * <p>
 * {@code trim-threshold} 降级为 <b>lag 告警阈值</b>（XLEN − Σ各组 XPENDING 超阈值 →
 * {@code messaging.stream.trim.threshold.exceeded} counter + warn），非物理裁剪上限。
 * <p>
 * SmartLifecycle（默认 phase）：独立单线程调度器；关闭先于 consumer pool（P2-9，phase DEFAULT-200）。
 */
public class StreamTrimTask implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamTrimTask.class);

    private final RedisStreamConsumerConnections connections;
    private final MessagingProperties properties;
    private final MessagingMetrics metrics;

    /** streamKey → 已注册 group 集合。 */
    private final ConcurrentMap<String, Set<String>> groupsByStream = new ConcurrentHashMap<>();

    /** KEYS[1]=stream；ARGV[1]=minid —— XTRIM MINID ~（主 stream 裁剪，P1-5）。 */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> XTRIM_MINID_SCRIPT =
        new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "return redis.call('XTRIM', KEYS[1], 'MINID', '~', ARGV[1])",
            Long.class);

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stream-trim-task");
            t.setDaemon(true);
            return t;
        });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public StreamTrimTask(RedisStreamConsumerConnections connections,
                          MessagingProperties properties, MessagingMetrics metrics) {
        this.connections = connections;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void register(String topic, String group) {
        String streamKey = new RedisStreamKeys(properties).streamKey(topic);
        groupsByStream.computeIfAbsent(streamKey, k -> ConcurrentHashMap.newKeySet()).add(group);
        log.info("StreamTrimTask registered: stream={}, group={}", streamKey, group);
    }

    public void unregister(String topic, String group) {
        String streamKey = new RedisStreamKeys(properties).streamKey(topic);
        Set<String> groups = groupsByStream.get(streamKey);
        if (groups != null) {
            groups.remove(group);
            if (groups.isEmpty()) {
                groupsByStream.remove(streamKey);
            }
        }
    }

    // ==================== 周期裁剪 ====================

    /** 周期裁剪（调度器调用；包内可见供测试直调）。 */
    void trim() {
        for (var entry : groupsByStream.entrySet()) {
            String streamKey = entry.getKey();
            Set<String> groups = entry.getValue();
            try {
                trimStream(streamKey, groups);
                checkLag(streamKey, groups);
            } catch (Exception e) {
                log.error("Stream trim/lag check failed (continuing next cycle): stream={}", streamKey, e);
            }
        }
    }

    private void trimStream(String streamKey, Set<String> groups) {
        var infos = connections.streamOps().groups(streamKey);
        String minDeliveredId = null;
        for (StreamInfo.XInfoGroup info : infos) {
            if (!groups.contains(info.groupName())) {
                continue;
            }
            String lastDelivered = info.lastDeliveredId();
            if (minDeliveredId == null || compareStreamId(lastDelivered, minDeliveredId) < 0) {
                minDeliveredId = lastDelivered;
            }
        }
        // 最小 last-delivered-id = "0-0"（group 从未消费）→ MINID ~ 0-0 不裁剪任何 entry，天然安全
        if (minDeliveredId == null || "0-0".equals(minDeliveredId)) {
            return;
        }
        // Lua 脚本 XTRIM（raw execute 对 multi-bulk/integer 回复解析不可靠，见 RedisStreamConsumerConnections）
        Long trimmed = connections.template().execute(XTRIM_MINID_SCRIPT, List.of(streamKey), minDeliveredId);
        if (trimmed != null && trimmed > 0) {
            log.info("Stream trimmed (MINID ~ {}): stream={}, entriesRemoved={}",
                minDeliveredId, streamKey, trimmed);
        }
    }

    private void checkLag(String streamKey, Set<String> groups) {
        Long xlen = connections.streamOps().size(streamKey);
        long pending = 0;
        for (String group : groups) {
            var summary = connections.streamOps().pending(streamKey, group);
            if (summary != null) {
                pending += summary.getTotalPendingMessages();
            }
        }
        long lag = xlen == null ? 0 : xlen - pending;
        long threshold = properties.redis().trimThreshold();
        if (lag > threshold) {
            metrics.recordTrimThresholdExceeded(streamKey);
            log.warn("Stream lag exceeds trim-threshold ({} > {}): stream={}, xlen={}, pending={}",
                lag, threshold, streamKey, xlen, pending);
        }
    }

    /** stream id "ms-seq" 数值比较（0-0 < 1690000000000-0）。 */
    private static int compareStreamId(String a, String b) {
        long[] pa = parseStreamId(a);
        long[] pb = parseStreamId(b);
        if (pa[0] != pb[0]) {
            return Long.compare(pa[0], pb[0]);
        }
        return Long.compare(pa[1], pb[1]);
    }

    private static long[] parseStreamId(String id) {
        int dash = id.indexOf('-');
        try {
            long ms = dash < 0 ? Long.parseLong(id) : Long.parseLong(id.substring(0, dash));
            long seq = dash < 0 ? 0 : Long.parseLong(id.substring(dash + 1));
            return new long[] {ms, seq};
        } catch (NumberFormatException e) {
            return new long[] {0, 0};
        }
    }

    // ==================== SmartLifecycle ====================

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            long intervalMs = properties.redis().trimPollInterval().toMillis();
            scheduler.scheduleAtFixedRate(this::trim, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            log.info("StreamTrimTask started: pollIntervalMs={}", intervalMs);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("StreamTrimTask stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 200;
    }
}
