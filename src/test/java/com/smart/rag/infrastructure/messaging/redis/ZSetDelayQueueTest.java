package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.ZSetDelayQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ZSetDelayQueue} 单测（P2-11，Testcontainers）：enqueue/drain 原子抢占；孤儿清理（P2-7）；
 * 多实例并发只一个 drain 成功（P1-3 ZREM 抢占）。
 */
class ZSetDelayQueueTest extends AbstractRedisStreamTest {

    private static final String ZSET = "retry-zset:SMART_RAG_t:g";
    private static final String HASH = "retry:SMART_RAG_t:g";
    private static final String STREAM = "stream:SMART_RAG_t";

    @BeforeEach
    void setUp() {
        flushAll();
    }

    private Map<String, String> payload(String id, String attempt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", "t");
        fields.put("tag", "");
        fields.put("dedupKey", id);
        fields.put("hashKey", "");
        fields.put("headers", "{}");
        fields.put("payload", "\"" + id + "\"");
        fields.put("bornTs", "1");
        fields.put("attempt", attempt);
        fields.put("contentType", "application/json");
        return fields;
    }

    @Test
    @DisplayName("enqueue → drain 回灌 stream，字段（含 attempt）完整流转")
    void enqueueThenDrain_reinjectsWithFields() {
        delayQueue().enqueue(ZSET, HASH, "r1", payload("r1", "3"), System.currentTimeMillis() - 1000,
            Duration.ofHours(2));

        ZSetDelayQueue.DrainResult result = delayQueue().drainToStream(ZSET, HASH, STREAM, 32,
            System.currentTimeMillis());

        assertEquals(1, result.reinjected());
        assertEquals(0, result.orphans());
        // zset/hash 清空
        assertEquals(0, business().opsForZSet().size(ZSET));
        assertEquals(0, business().opsForHash().size(HASH));
        // stream 回灌字段完整（P0-2：attempt 随回灌携带）
        List<MapRecord<String, Object, Object>> records =
            business().opsForStream().range(STREAM, org.springframework.data.domain.Range.unbounded());
        assertEquals(1, records.size());
        Map<?, ?> fields = records.getFirst().getValue();
        assertEquals("r1", fields.get("dedupKey"));
        assertEquals("3", fields.get("attempt"));
        assertEquals("application/json", fields.get("contentType"));
    }

    @Test
    @DisplayName("未到期条目不 drain")
    void futureDue_notDrained() {
        delayQueue().enqueue(ZSET, HASH, "r1", payload("r1", "1"),
            System.currentTimeMillis() + 60_000, Duration.ofHours(2));

        ZSetDelayQueue.DrainResult result = delayQueue().drainToStream(ZSET, HASH, STREAM, 32,
            System.currentTimeMillis());

        assertEquals(0, result.reinjected());
        assertEquals(1, business().opsForZSet().size(ZSET));
        assertEquals(0, business().opsForStream().size(STREAM));
    }

    @Test
    @DisplayName("孤儿（zset 有 id、hash 无 payload）被清理并计数（P2-7）")
    void orphanEntry_cleanedAndCounted() {
        business().opsForZSet().add(ZSET, "orphan-id", System.currentTimeMillis() - 1000);

        ZSetDelayQueue.DrainResult result = delayQueue().drainToStream(ZSET, HASH, STREAM, 32,
            System.currentTimeMillis());

        assertEquals(0, result.reinjected());
        assertEquals(1, result.orphans());
        assertEquals(0, business().opsForZSet().size(ZSET));
    }

    @Test
    @DisplayName("多实例并发 drain：单 Lua ZREM 抢占，每条只回灌一次（P1-3）")
    void concurrentDrain_noDuplicates() throws Exception {
        int total = 20;
        for (int i = 0; i < total; i++) {
            delayQueue().enqueue(ZSET, HASH, "r" + i, payload("r" + i, "1"),
                System.currentTimeMillis() - 1000, Duration.ofHours(2));
        }

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger reinjected = new AtomicInteger();
        AtomicInteger orphans = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int round = 0; round < 50; round++) {
                        ZSetDelayQueue.DrainResult r = delayQueue().drainToStream(
                            ZSET, HASH, STREAM, 32, System.currentTimeMillis());
                        reinjected.addAndGet(r.reinjected());
                        orphans.addAndGet(r.orphans());
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent drain timed out");
        pool.shutdownNow();

        // 20 条全部回灌且无重复（ZREM 抢占保证）
        assertEquals(total, reinjected.get());
        assertEquals(0, orphans.get());
        assertEquals(total, business().opsForStream().size(STREAM));
        assertEquals(0, business().opsForZSet().size(ZSET));
        assertEquals(0, business().opsForHash().size(HASH));
    }

    @Test
    @DisplayName("回灌 XADD 不带 MAXLEN（P1-5：主 stream 裁剪由 StreamTrimTask 负责）")
    void drainXadd_hasNoMaxlenTrim() {
        delayQueue().enqueue(ZSET, HASH, "r1", payload("r1", "1"),
            System.currentTimeMillis() - 1000, Duration.ofHours(2));

        delayQueue().drainToStream(ZSET, HASH, STREAM, 32, System.currentTimeMillis());

        List<MapRecord<String, Object, Object>> records =
            business().opsForStream().range(STREAM, org.springframework.data.domain.Range.unbounded());
        assertEquals(1, records.size());
        assertFalse(records.getFirst().getValue().isEmpty());
    }
}
