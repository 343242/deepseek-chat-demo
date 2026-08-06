package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.ZSetDelayQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link RetrySweeper} 单测（P0-1/P0-2/P1-3/P2-7，Testcontainers）：
 * 失败转 ZSET；attempt 字段跨回灌累加；maxAttempts → DLQ；单 Lua 原子回灌；孤儿清理。
 */
class RetrySweeperTest extends AbstractRedisStreamTest {

    private static final String TOPIC = "chat_message_save";
    private static final String GROUP = "save-group";

    private RetrySweeper sweeper;
    private RedisStreamConsumerRunner<?> runner;

    @BeforeEach
    void setUp() {
        flushAll();
        runner = mock(RedisStreamConsumerRunner.class);
        when(runner.topic()).thenReturn(TOPIC);
        when(runner.group()).thenReturn(GROUP);
        sweeper = new RetrySweeper(delayQueue(), keys(), backoff(), props(), metrics(), dlqWriter());
        sweeper.register(runner);
    }

    private MapRecord<String, String, String> recordWithAttempt(String attempt, String dedupKey) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", TOPIC);
        fields.put("tag", "");
        fields.put("dedupKey", dedupKey);
        fields.put("hashKey", "");
        fields.put("headers", "{}");
        fields.put("payload", "\"payload\"");
        fields.put("bornTs", "1");
        fields.put("attempt", attempt);
        fields.put("contentType", "application/json");
        return MapRecord.create(keys().streamKey(TOPIC), fields).withId(RecordId.of("1000-0"));
    }

    private String retryZset() {
        return keys().retryZsetKey(TOPIC, GROUP);
    }

    private String retryHash() {
        return keys().retryHashKey(TOPIC, GROUP);
    }

    private String dlqKey() {
        return keys().dlqKey(TOPIC, GROUP);
    }

    @Test
    @DisplayName("失败 → XACK + ZSET 入队，attempt 0→1，score = now + 第 1 档退避")
    void routeToRetry_schedulesWithBackoff() {
        sweeper.routeToRetry(runner, recordWithAttempt("0", "k1"), new RuntimeException("boom"));

        // P0-1：XACK（sweeper 接管，不留 PEL）
        verify(runner).ack(RecordId.of("1000-0"));
        // ZSET 1 条（member = 随机 retryId），score 在 [now+1s-ε, now+1s+ε]
        Set<String> members = business().opsForZSet().range(retryZset(), 0, -1);
        assertEquals(1, members.size());
        String retryId = members.iterator().next();
        Double score = business().opsForZSet().score(retryZset(), retryId);
        long now = System.currentTimeMillis();
        assertTrue(score >= now + 900 && score <= now + 1100, "score=" + score);
        // hash payload 携带 attempt=1（P0-2）
        String stored = (String) business().opsForHash().get(retryHash(), retryId);
        assertNotNull(stored);
        assertTrue(stored.contains("\"attempt\":\"1\""), stored);
    }

    @Test
    @DisplayName("attempt 随消息字段累加：第 5 次失败 → 第 5 档退避（1m）")
    void routeToRetry_attemptFieldProgresses() {
        sweeper.routeToRetry(runner, recordWithAttempt("4", "k2"), new RuntimeException("boom"));

        Set<String> members = business().opsForZSet().range(retryZset(), 0, -1);
        assertEquals(1, members.size());
        Double score = business().opsForZSet().score(retryZset(), members.iterator().next());
        long now = System.currentTimeMillis();
        assertTrue(score >= now + 59_000 && score <= now + 61_000, "score=" + score);
        String stored = (String) business().opsForHash().get(retryHash(), members.iterator().next());
        assertTrue(stored.contains("\"attempt\":\"5\""), stored);
    }

    @Test
    @DisplayName("attempt 超过 maxAttempts → DLQ（reason=RETRY_EXHAUSTED）+ XACK，不进 ZSET")
    void routeToRetry_exhaustedGoesToDlq() {
        int maxAttempts = props().redis().maxAttempts();
        sweeper.routeToRetry(runner, recordWithAttempt(String.valueOf(maxAttempts), "k3"),
            new RuntimeException("boom"));

        verify(runner).ack(RecordId.of("1000-0"));
        assertEquals(0, business().opsForZSet().size(retryZset()));
        long dlqLen = business().opsForStream().size(dlqKey());
        assertEquals(1, dlqLen);
        List<MapRecord<String, Object, Object>> dlq = business().opsForStream()
            .range(dlqKey(), Range.unbounded());
        Map<?, ?> fields = dlq.getFirst().getValue();
        assertEquals("RETRY_EXHAUSTED", fields.get("reason"));
        assertEquals(TOPIC, fields.get("originalTopic"));
        assertEquals(GROUP, fields.get("originGroup"));
        assertEquals("k3", fields.get("dedupKey"));
    }

    @Test
    @DisplayName("drain 原子回灌主 stream：attempt 字段随回灌携带（P0-2），zset/hash 清空")
    void drain_reinjectsWithAttempt() {
        sweeper.routeToRetry(runner, recordWithAttempt("0", "k4"), new RuntimeException("boom"));
        // 强制到期（正常由调度器按退避到期；这里直接改 score 模拟到期）
        String retryId = business().opsForZSet().range(retryZset(), 0, -1).iterator().next();
        business().opsForZSet().add(retryZset(), retryId, System.currentTimeMillis() - 1000);

        sweeper.drain();

        assertEquals(0, business().opsForZSet().size(retryZset()));
        assertEquals(0, business().opsForHash().size(retryHash()));
        List<MapRecord<String, Object, Object>> stream = business().opsForStream()
            .range(keys().streamKey(TOPIC), Range.unbounded());
        assertEquals(1, stream.size());
        Map<?, ?> fields = stream.getFirst().getValue();
        assertEquals("1", fields.get("attempt"));   // P0-2：跨回灌累加
        assertEquals("k4", fields.get("dedupKey"));
        assertEquals("application/json", fields.get("contentType"));
    }

    @Test
    @DisplayName("孤儿条目 drain 时清理并计数（P2-7），正常条目不受影响")
    void drain_cleansOrphans() {
        sweeper.routeToRetry(runner, recordWithAttempt("0", "k5"), new RuntimeException("boom"));
        String retryId = business().opsForZSet().range(retryZset(), 0, -1).iterator().next();
        business().opsForZSet().add(retryZset(), "orphan", System.currentTimeMillis() - 1000);
        business().opsForZSet().add(retryZset(), retryId, System.currentTimeMillis() - 1000);

        sweeper.drain();

        assertEquals(0, business().opsForZSet().size(retryZset()));
        // 正常 1 条回灌；孤儿仅清理
        assertEquals(1, business().opsForStream().size(keys().streamKey(TOPIC)));
    }

    @Test
    @DisplayName("多实例并发 drain：同一条只回灌一次（P1-3 单 Lua ZREM 抢占）")
    void concurrentDrain_singleReinjection() throws Exception {
        int total = 10;
        for (int i = 0; i < total; i++) {
            business().opsForHash().put(retryHash(), "id" + i, payloadJson("id" + i));
            business().opsForZSet().add(retryZset(), "id" + i, System.currentTimeMillis() - 1000);
        }

        RetrySweeper sweeper2 = new RetrySweeper(delayQueue(), keys(), backoff(), props(),
            new MessagingMetrics(null), dlqWriter());
        sweeper2.register(runner);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    sweeper.drain();
                    sweeper2.drain();
                    sweeper.drain();
                    sweeper2.drain();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(total, business().opsForStream().size(keys().streamKey(TOPIC)));
        assertEquals(0, business().opsForZSet().size(retryZset()));
        assertEquals(0, business().opsForHash().size(retryHash()));
    }

    private static String payloadJson(String id) {
        return "{\"topic\":\"chat_message_save\",\"tag\":\"\",\"dedupKey\":\"" + id
            + "\",\"hashKey\":\"\",\"headers\":\"{}\",\"payload\":\"\\\"p\\\"\",\"bornTs\":\"1\","
            + "\"attempt\":\"2\",\"contentType\":\"application/json\"}";
    }
}
