package com.smart.rag.infrastructure.messaging.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.ConsumerMode;
import com.smart.rag.infrastructure.messaging.JacksonMessageCodec;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.TracePropagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FIFO 有序（R6，Testcontainers + Redisson RLock）：bus 不分区，per-documentId 串行由
 * {@code RLock(ETL_LOCK_PREFIX + documentId)} 保证（镜像 EtlDispatchServiceImpl:82-94 的锁模式）。
 * 同 documentId 消息并发消费时处理互不重叠、顺序保持；不同 documentId 可并行。
 */
class RedisStreamFifoTest extends AbstractRedisStreamTest {

    private static final String TOPIC = "rag_index_document";
    private static final String GROUP = "index-group";
    private static final String ETL_LOCK_PREFIX = "smart-rag:etl:lock:";

    private RedissonClient redisson;
    private RedisStreamConsumerRunner<String> runner;
    private final AtomicInteger processed = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> timeline = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        flushAll();
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisson = Redisson.create(config);

        runner = new RedisStreamConsumerRunner<>(TOPIC, GROUP, "app:fifo-test",
            ConsumerConfig.builder().consumerMode(ConsumerMode.SIMPLE).concurrency(3).build(),
            String.class, msg -> processWithLock(msg),
            new JacksonMessageCodec(new ObjectMapper()), TracePropagator.NO_OP,
            metrics(), props(), connections(),
            new RetrySweeper(delayQueue(), keys(), backoff(), props(), metrics(), dlqWriter()),
            dlqWriter());
        runner.start();
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.stop();
        }
        redisson.shutdown();
    }

    /** 镜像 EtlDispatchServiceImpl 的 RLock 串行模式。 */
    private void processWithLock(MessageEnvelope<String> msg) {
        String documentId = msg.hashKey();
        RLock lock = redisson.getLock(ETL_LOCK_PREFIX + documentId);
        try {
            if (!lock.tryLock(30, -1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lock acquisition failed: " + documentId);
            }
            timeline.add(documentId + ":start");
            try {
                Thread.sleep(80);   // 模拟 ETL 处理时长
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            timeline.add(documentId + ":end");
            processed.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void sendForDocument(String documentId, String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", TOPIC);
        fields.put("tag", "");
        fields.put("dedupKey", documentId + ":" + payload);
        fields.put("hashKey", documentId);
        fields.put("headers", "{}");
        fields.put("payload", "\"" + payload + "\"");
        fields.put("bornTs", String.valueOf(System.currentTimeMillis()));
        fields.put("attempt", "0");
        fields.put("contentType", "application/json");
        business().opsForStream().add(MapRecord.create(keys().streamKey(TOPIC), fields));
    }

    @Test
    @DisplayName("同 documentId 消息 RLock 串行（无重叠、保序），不同 documentId 并行")
    void sameDocumentId_serializedByLock() {
        sendForDocument("doc-1", "a");
        sendForDocument("doc-1", "b");
        sendForDocument("doc-2", "c");
        sendForDocument("doc-2", "d");

        await().atMost(20, TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(4, processed.get()));

        List<String> events = new ArrayList<>(timeline);
        // 同 documentId：start 必须成对（不允许 doc-1:start 后紧跟另一条 doc-1:start）
        boolean doc1Overlap = false;
        boolean doc2Overlap = false;
        for (int i = 0; i + 1 < events.size(); i++) {
            if (events.get(i).equals("doc-1:start") && events.get(i + 1).equals("doc-1:start")) {
                doc1Overlap = true;
            }
            if (events.get(i).equals("doc-2:start") && events.get(i + 1).equals("doc-2:start")) {
                doc2Overlap = true;
            }
        }
        assertFalse(doc1Overlap, "doc-1 并发重叠: " + events);
        assertFalse(doc2Overlap, "doc-2 并发重叠: " + events);

        // 保序：同 documentId 的第一条消息处理完先于第二条开始（first end < last start）
        int firstEnd = events.indexOf("doc-1:end");
        int lastStart = events.lastIndexOf("doc-1:start");
        assertTrue(firstEnd < lastStart, "同 documentId 顺序被破坏: " + events);
        // 不同 documentId 允许并行（存在交叠区间）
        boolean parallelAcrossDocs = false;
        for (int i = 0; i < events.size(); i++) {
            if ("doc-1:start".equals(events.get(i))) {
                int idx = i;
                boolean anyDoc2InBetween = events.subList(idx + 1, events.size()).stream()
                    .anyMatch(e -> e.startsWith("doc-2:"));
                parallelAcrossDocs = parallelAcrossDocs || anyDoc2InBetween;
            }
        }
        assertTrue(parallelAcrossDocs, "不同 documentId 未并行（concurrency=3 应并发）: " + events);
    }
}
