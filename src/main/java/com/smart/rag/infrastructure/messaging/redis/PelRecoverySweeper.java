package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PEL 崩溃恢复 sweeper（design §5，R4）——consumer 已 XREADGROUP 未 XACK 即崩溃 → 消息留 PEL
 * 无人处理；本 sweeper 用 {@code XAUTOCLAIM} 转移 idle 过久的 PEL 条目给当前 consumer。
 * <ul>
 *   <li>{@code minIdleMs = pel-min-idle-ms}（默认 40min）> 最大处理时长（ETL 30min + 10min margin，
 *       启动期断言），避免抢走正在处理的消息；</li>
 *   <li>多实例并发安全：XAUTOCLAIM 原子转移归属，只一个实例 claim；</li>
 *   <li><b>P1-6 异步派发</b>：claim 后派发到该 subscription 的 processingPool 异步执行
 *       （不在 sweeper 调度线程同步 handle，避免 ETL 长任务阻塞 sweeper）；</li>
 *   <li>与 RetrySweeper 互补不重叠：RetrySweeper 处理"已 XACK 转延迟队列"的正常重试；
 *       本 sweeper 处理"未 XACK 留 PEL"的崩溃场景。</li>
 * </ul>
 * SmartLifecycle phase {@code DEFAULT-200}：先于 consumer pool（DEFAULT-100）关闭（P2-9）。
 */
public class PelRecoverySweeper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PelRecoverySweeper.class);

    private final RedisStreamConsumerConnections connections;
    private final MessagingProperties properties;
    private final MessagingMetrics metrics;

    /** topic:group → runner（subscribe 时 register，close 时 unregister）。 */
    private final ConcurrentMap<String, RedisStreamConsumerRunner<?>> runners = new ConcurrentHashMap<>();

    /** 独立单线程调度器（P1-6：与 RetrySweeper 各用独立线程池，长任务互不阻塞）。 */
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pel-recovery-sweeper");
            t.setDaemon(true);
            return t;
        });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PelRecoverySweeper(RedisStreamConsumerConnections connections,
                              MessagingProperties properties, MessagingMetrics metrics) {
        this.connections = connections;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void register(RedisStreamConsumerRunner<?> runner) {
        runners.put(runner.topic() + ":" + runner.group(), runner);
        log.info("PelRecoverySweeper registered: topic={}, group={}", runner.topic(), runner.group());
    }

    public void unregister(String topic, String group) {
        runners.remove(topic + ":" + group);
        log.info("PelRecoverySweeper unregistered: topic={}, group={}", topic, group);
    }

    // ==================== 周期回收 ====================

    /** 周期回收（调度器调用；包内可见供测试直调）。 */
    void drain() {
        for (RedisStreamConsumerRunner<?> runner : runners.values()) {
            String streamKey = new RedisStreamKeys(properties).streamKey(runner.topic());
            String group = runner.group();
            try {
                List<MapRecord<String, ?, ?>> claimed = xautoclaim(
                    streamKey, group, RedisStreamInstance.consumerName(properties),
                    properties.redis().pelMinIdle().toMillis(),
                    properties.redis().readBatch());
                for (MapRecord<String, ?, ?> record : claimed) {
                    // P1-6：异步派发到该 subscription 的 processingPool，不在 sweeper 线程同步 handle
                    runner.dispatchToProcessingPool(() -> runner.handle(record));
                }
                if (!claimed.isEmpty()) {
                    log.info("PEL recovery claimed: stream={}, group={}, count={}",
                        streamKey, group, claimed.size());
                }
            } catch (Exception e) {
                log.error("PEL recovery drain failed (continuing next cycle): stream={}, group={}",
                    streamKey, group, e);
            }
        }
    }

    /**
     * XAUTOCLAIM 等价（Lua 脚本原子实现）：{@code XPENDING ... IDLE minIdleMs → XCLAIM}。
     * <p>
     * SDR 无 XAUTOCLAIM 包装，raw execute 对 multi-bulk 回复会截断（返回最后字段值）——
     * 走单 Lua 脚本：脚本内原子完成过滤 + 转移归属（XPENDING 结果与 XCLAIM 之间无竞态）。
     * 返回 claimed 的完整记录（id + 字段 map）。
     */
    @SuppressWarnings("unchecked")
    private List<MapRecord<String, ?, ?>> xautoclaim(
            String streamKey, String group, String consumerName, long minIdleMs, int batch) {
        List<Object> claimed = connections.template().execute(XAUTOCLAIM_SCRIPT,
            List.of(streamKey, group, consumerName),
            String.valueOf(minIdleMs), String.valueOf(batch));
        List<MapRecord<String, ?, ?>> records = new ArrayList<>();
        if (claimed == null) {
            return records;
        }
        for (Object o : claimed) {
            if (!(o instanceof List<?> entry) || entry.size() < 2) {
                continue;
            }
            String id = String.valueOf(entry.get(0));
            Map<String, Object> fields = new LinkedHashMap<>();
            if (entry.get(1) instanceof List<?> fv) {
                for (int i = 0; i + 1 < fv.size(); i += 2) {
                    fields.put(String.valueOf(fv.get(i)), String.valueOf(fv.get(i + 1)));
                }
            }
            records.add(MapRecord.create(streamKey, fields).withId(RecordId.of(id)));
        }
        return records;
    }

    /**
     * KEYS[1]=stream KEYS[2]=group KEYS[3]=consumer；ARGV[1]=minIdleMs ARGV[2]=batch。
     * XPENDING 按 IDLE 过滤 → XCLAIM 转移归属（min-idle 0，脚本内原子，无竞态）。
     */
    @SuppressWarnings("rawtypes")
    private static final org.springframework.data.redis.core.script.RedisScript<List> XAUTOCLAIM_SCRIPT =
        new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local minIdle = tonumber(ARGV[1]) " +
            "local count = tonumber(ARGV[2]) " +
            "local pending = redis.call('XPENDING', KEYS[1], KEYS[2], 'IDLE', minIdle, '-', '+', count) " +
            "local claimed = {} " +
            "for _, entry in ipairs(pending) do " +
            "  local result = redis.call('XCLAIM', KEYS[1], KEYS[2], KEYS[3], 0, entry[1]) " +
            "  if result and #result > 0 then claimed[#claimed + 1] = result[1] end " +
            "end " +
            "return claimed",
            List.class);

    // ==================== SmartLifecycle（P2-9：先于 consumer 关闭） ====================

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            long intervalMs = properties.redis().retryPollInterval().toMillis();
            scheduler.scheduleAtFixedRate(this::drain, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            log.info("PelRecoverySweeper started: pollIntervalMs={}, pelMinIdleMs={}",
                intervalMs, properties.redis().pelMinIdle().toMillis());
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
            log.info("PelRecoverySweeper stopped");
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
