package com.smart.rag.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ZSET 延迟队列通用组件（design §4.5，P2-11 评审"通用性"）——抽离"ZADD(score=到期) +
 * ZRANGEBYSCORE + Lua 抢占 + 回灌"模式，供 {@link com.smart.rag.infrastructure.messaging.redis.RetrySweeper}
 * 与 child 2 OutboxRelay 等延迟重试场景复用。
 * <p>
 * 数据布局：payload 存 hash（field=id），zset 只存 id（score=到期 ms），避免 zset value 携带大体量 payload。
 * <ul>
 *   <li>{@link #enqueue} — 单 Lua 原子 {@code HSET + EXPIRE + ZADD}（P2-14：杜绝"HSET 成功 ZADD 失败 → hash 孤儿"）；</li>
 *   <li>{@link #drainToStream} — 单 Lua 原子 {@code ZRANGEBYSCORE → ZREM 抢占 → HGET → XADD 回灌 → HDEL}
 *       （P1-3：中间崩溃由脚本原子性兜底，杜绝 ZREM 成功 XADD 前崩溃丢消息；多实例并发只一个回灌）。</li>
 * </ul>
 * 注意：drainToStream 的 XADD 回灌不携带 MAXLEN —— 主 stream 物理裁剪由
 * {@link com.smart.rag.infrastructure.messaging.redis.StreamTrimTask} 按 XINFO 最小
 * last-delivered-id 做 {@code XTRIM MINID ~}（P1-5），避免积压时丢未投递消息。
 */
public class ZSetDelayQueue {

    private static final Logger log = LoggerFactory.getLogger(ZSetDelayQueue.class);

    private final StringRedisTemplate redisTemplate;

    public ZSetDelayQueue(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 原子入队：HSET + EXPIRE（hash key 整体 TTL，覆盖最大退避窗口 ×2，防极端残留）+ ZADD。
     *
     * @param zsetKey  延迟 zset key（score = 到期 ms）
     * @param hashKey  payload hash key（field = id）
     * @param id       稳定标识（跨回灌保持一致）
     * @param payload  原字段 map（JSON 序列化入 hash）
     * @param dueAtMs  到期时间戳（epoch ms）
     * @param hashTtl  hash key TTL（覆盖最大退避窗口 ×2 + 缓冲）
     */
    public void enqueue(String zsetKey, String hashKey, String id,
                        Map<String, String> payload, long dueAtMs, Duration hashTtl) {
        redisTemplate.execute(ENQUEUE_SCRIPT,
            List.of(hashKey, zsetKey),
            id,
            payloadJson(payload),
            String.valueOf(dueAtMs),
            String.valueOf(hashTtl.toSeconds()));
    }

    /**
     * 原子批量出队并回灌 Redis Stream（单 Lua：ZRANGEBYSCORE → ZREM 抢占 → HGET → XADD → HDEL）。
     * 回灌字段 = hash 中 payload map 的全部字段（含随消息流转的 attempt，P0-2）。
     *
     * @param zsetKey   延迟 zset key
     * @param hashKey   payload hash key
     * @param streamKey 回灌目标 stream key
     * @param batch     单批上限
     * @param nowMs     当前时间（到期 score ≤ now 的条目出队）
     * @return 回灌条目数与孤儿清理数；孤儿（HGET null，HSET 曾失败/zset 残留）被 ZREM 清理并计数
     */
    @SuppressWarnings("unchecked")
    public DrainResult drainToStream(String zsetKey, String hashKey, String streamKey,
                                     int batch, long nowMs) {
        List<Long> result = (List<Long>) redisTemplate.execute(DRAIN_TO_STREAM_SCRIPT,
            List.of(zsetKey, hashKey, streamKey),
            String.valueOf(nowMs),
            String.valueOf(batch));
        long reinjected = result == null || result.isEmpty() ? 0 : result.get(0);
        long orphans = result == null || result.size() < 2 ? 0 : result.get(1);
        if (orphans > 0) {
            log.warn("Retry queue orphan cleanup: orphans={}, zsetKey={}", orphans, zsetKey);
        }
        return new DrainResult((int) reinjected, (int) orphans);
    }

    public record DrainResult(int reinjected, int orphans) {}

    private static String payloadJson(Map<String, String> payload) {
        StringBuilder sb = new StringBuilder(payload.size() * 32 + 2);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : payload.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"")
                .append(escape(e.getValue() == null ? "" : e.getValue())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** KEYS[1]=hash KEYS[2]=zset；ARGV[1]=id ARGV[2]=payloadJson ARGV[3]=dueAtMs ARGV[4]=ttlSeconds */
    private static final RedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>(
        "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) " +
        "redis.call('EXPIRE', KEYS[1], ARGV[4]) " +
        "redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1]) " +
        "return 1",
        Long.class
    );

    /**
     * KEYS[1]=zset KEYS[2]=hash KEYS[3]=stream；ARGV[1]=now ARGV[2]=batch。
     * ZREM 抢占（返回 1 才继续）→ HGET → XADD 回灌（payload 全字段）→ HDEL。
     * P2-7：HGET null（孤儿）→ 清理（ZREM 已在抢占时移除）+ 计数，单条异常不中止整批（pcall）。
     * P0-2：attempt 随 payload 字段随回灌携带。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DRAIN_TO_STREAM_SCRIPT = new DefaultRedisScript<>(
        "local now = tonumber(ARGV[1]) " +
        "local batch = tonumber(ARGV[2]) " +
        "local entries = redis.call('ZRANGEBYSCORE', KEYS[1], 0, now, 'LIMIT', 0, batch) " +
        "local reinjected = 0 " +
        "local orphans = 0 " +
        "for _, id in ipairs(entries) do " +
        "  if redis.call('ZREM', KEYS[1], id) == 1 then " +
        "    local data = redis.call('HGET', KEYS[2], id) " +
        "    if data then " +
        "      local ok, fields = pcall(cjson.decode, data) " +
        "      if ok and type(fields) == 'table' then " +
        "        local args = {} " +
        "        for k, v in pairs(fields) do " +
        "          table.insert(args, k) " +
        "          table.insert(args, tostring(v)) " +
        "        end " +
        "        redis.call('XADD', KEYS[3], '*', unpack(args)) " +
        "        redis.call('HDEL', KEYS[2], id) " +
        "        reinjected = reinjected + 1 " +
        "      else " +
        "        orphans = orphans + 1 " +
        "      end " +
        "    else " +
        "      orphans = orphans + 1 " +
        "    end " +
        "  end " +
        "end " +
        "return {reinjected, orphans}",
        List.class
    );
}
