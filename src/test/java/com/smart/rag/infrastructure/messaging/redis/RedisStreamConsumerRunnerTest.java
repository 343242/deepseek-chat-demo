package com.smart.rag.infrastructure.messaging.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.PermanentConsumeException;
import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.ConsumerMode;
import com.smart.rag.infrastructure.messaging.JacksonMessageCodec;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import com.smart.rag.infrastructure.messaging.TracePropagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link RedisStreamConsumerRunner} 单测（P0-1/P1-4 + 退避重连，Testcontainers + mock）：
 * XREADGROUP→handle→XACK；可重试→XACK+ZSET（P0-1 不留 PEL）；Permanent→DLQ（P2-8 带 MAXLEN）；
 * pollLoop 连接失败指数退避重连（成功即 reset）；消费连接隔离（业务连接不被 BLOCK 阻塞）。
 */
class RedisStreamConsumerRunnerTest extends AbstractRedisStreamTest {

    private static final String TOPIC = "chat_message_save";
    private static final String GROUP = "save-group";
    private static final String CONSUMER = "app:test-runner";

    private RetrySweeper sweeper;
    private RedisStreamConsumerRunner<String> runner;
    private final AtomicInteger handlerCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        flushAll();
        sweeper = new RetrySweeper(delayQueue(), keys(), backoff(), props(), metrics(), dlqWriter());
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.stop();
        }
    }

    private RedisStreamConsumerRunner<String> startRunner(ConsumerConfig config, MessageHandler<String> handler) {
        runner = new RedisStreamConsumerRunner<>(TOPIC, GROUP, CONSUMER, config, String.class,
            handler, new JacksonMessageCodec(new ObjectMapper()), TracePropagator.NO_OP,
            metrics(), props(), connections(), sweeper, dlqWriter());
        runner.start();
        return runner;
    }

    private void sendMessage(String dedupKey, String payloadJson) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", TOPIC);
        fields.put("tag", "");
        fields.put("dedupKey", dedupKey);
        fields.put("hashKey", "");
        fields.put("headers", "{}");
        fields.put("payload", payloadJson);
        fields.put("bornTs", String.valueOf(System.currentTimeMillis()));
        fields.put("attempt", "0");
        fields.put("contentType", "application/json");
        business().opsForStream().add(MapRecord.create(keys().streamKey(TOPIC), fields));
    }

    private long pendingCount() {
        var summary = connections().streamOps().pending(keys().streamKey(TOPIC), GROUP);
        return summary == null ? 0 : summary.getTotalPendingMessages();
    }

    @Test
    @DisplayName("正常路径：XREADGROUP → handler → XACK，PEL 无残留")
    void happyPath_consumeAndAck() {
        MessageHandler<String> handler = msg -> handlerCalls.incrementAndGet();
        startRunner(ConsumerConfig.builder().consumerMode(ConsumerMode.SIMPLE).concurrency(1).build(), handler);
        sendMessage("k1", "\"hello\"");

        await().atMost(15, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(1, handlerCalls.get()));
        // P0-1：成功 → XACK，PEL 清空
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(0, pendingCount()));
    }

    @Test
    @DisplayName("可重试失败 → XACK + ZSET（P0-1 不留 PEL），不进 DLQ")
    void retryableFailure_acksAndQueuesRetry() {
        MessageHandler<String> handler = msg -> {
            handlerCalls.incrementAndGet();
            throw new RuntimeException("transient db failure");
        };
        startRunner(ConsumerConfig.builder().consumerMode(ConsumerMode.SIMPLE).concurrency(1).build(), handler);
        sendMessage("k2", "\"x\"");

        await().atMost(15, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertTrue(handlerCalls.get() >= 1));
        // P0-1：XACK 移出 PEL + 转入 ZSET（sweeper 接管）
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertEquals(0, pendingCount());
                assertEquals(1, business().opsForZSet().size(keys().retryZsetKey(TOPIC, GROUP)));
            });
        assertEquals(0, business().opsForStream().size(keys().dlqKey(TOPIC, GROUP)));
    }

    @Test
    @DisplayName("PermanentConsumeException → XACK + DLQ（reason=PERMANENT，带 MAXLEN），不进 ZSET")
    void permanentFailure_goesToDlq() {
        MessageHandler<String> handler = msg -> {
            handlerCalls.incrementAndGet();
            throw new PermanentConsumeException("poison message");
        };
        startRunner(ConsumerConfig.builder().consumerMode(ConsumerMode.SIMPLE).concurrency(1).build(), handler);
        sendMessage("k3", "\"poison\"");

        await().atMost(15, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertTrue(handlerCalls.get() >= 1));
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(0, pendingCount());
            assertEquals(1, business().opsForStream().size(keys().dlqKey(TOPIC, GROUP)));
            assertEquals(0, business().opsForZSet().size(keys().retryZsetKey(TOPIC, GROUP)));
        });
        List<MapRecord<String, Object, Object>> dlq = business().opsForStream()
            .range(keys().dlqKey(TOPIC, GROUP), Range.unbounded());
        assertEquals("PERMANENT", dlq.getFirst().getValue().get("reason"));
        assertEquals(GROUP, dlq.getFirst().getValue().get("originGroup"));
    }

    @Test
    @DisplayName("payload 解码失败（垃圾数据）→ PermanentConsumeException → DLQ")
    void decodeFailure_goesToDlq() {
        MessageHandler<String> handler = msg -> handlerCalls.incrementAndGet();
        startRunner(ConsumerConfig.builder().consumerMode(ConsumerMode.SIMPLE).concurrency(1).build(), handler);
        sendMessage("k4", "NOT_JSON{{{");

        await().atMost(15, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(1, business().opsForStream().size(keys().dlqKey(TOPIC, GROUP))));
        assertEquals(0, handlerCalls.get());
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(0, pendingCount()));
    }

    @Test
    @DisplayName("pollLoop 连接级失败 → 指数退避重连（成功即 reset），消息不丢")
    @SuppressWarnings("unchecked")
    void pollLoop_backsOffAndRecovers() throws Exception {
        RedisStreamConsumerConnections mockedConns = mock(RedisStreamConsumerConnections.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        when(mockedConns.streamOps()).thenReturn(ops);
        when(mockedConns.executeCommand(anyString(), any(String[].class))).thenReturn(null);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", TOPIC);
        fields.put("tag", "");
        fields.put("dedupKey", "k5");
        fields.put("hashKey", "");
        fields.put("headers", "{}");
        fields.put("payload", "\"recovered\"");
        fields.put("bornTs", "1");
        fields.put("attempt", "0");
        fields.put("contentType", "application/json");
        MapRecord<String, String, String> record =
            MapRecord.create(keys().streamKey(TOPIC), fields).withId(RecordId.of("1000-0"));

        // 前 2 次连接失败 → 退避 1s、2s → 第 3 次成功投递 1 条 → 之后空拉取（成功即 reset）
        when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
            .thenThrow(new RedisConnectionFailureException("connection refused"))
            .thenThrow(new RedisConnectionFailureException("connection refused"))
            .thenReturn(List.of(record))
            .thenReturn(List.of());

        MessagingMetrics metricsMock = mock(MessagingMetrics.class);
        AtomicInteger calls = new AtomicInteger();
        RedisStreamConsumerRunner<String> mockedRunner = new RedisStreamConsumerRunner<>(TOPIC, GROUP,
            CONSUMER, ConsumerConfig.builder().consumerMode(ConsumerMode.PUSH).concurrency(1).build(),
            String.class, msg -> calls.incrementAndGet(),
            new JacksonMessageCodec(new ObjectMapper()), TracePropagator.NO_OP,
            metricsMock, props(), mockedConns, mock(RetrySweeper.class), mock(RedisStreamDeadLetterWriter.class));
        try {
            mockedRunner.start();
            await().atMost(15, java.util.concurrent.TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(1, calls.get()));
        } finally {
            mockedRunner.stop();
        }
        verify(metricsMock, atLeastOnce()).recordConsumeConnectionFailure(TOPIC, GROUP);
    }

    @Test
    @DisplayName("P1-4 消费连接隔离：XREADGROUP BLOCK 期间业务 Redis 操作不被阻塞")
    void consumerBlock_doesNotBlockBusinessConnection() throws InterruptedException {
        MessageHandler<String> handler = msg -> { };
        // readBlock 默认 2000ms；PUSH 1 线程持续 BLOCK
        startRunner(ConsumerConfig.builder().consumerMode(ConsumerMode.PUSH).concurrency(1).build(), handler);
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertTrue(runner.isRunning()));

        // 等 poll 线程进入 XREADGROUP BLOCK
        Thread.sleep(500);
        long start = System.nanoTime();
        business().execute((org.springframework.data.redis.core.RedisCallback<String>) conn -> conn.ping());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 500, "business ping blocked by consumer BLOCK: " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("close 幂等：stop 两次不抛异常")
    void stop_isIdempotent() {
        startRunner(ConsumerConfig.builder().consumerMode(ConsumerMode.SIMPLE).concurrency(1).build(),
            msg -> { });
        runner.stop();
        assertDoesNotThrow(() -> runner.stop());
    }

    @Test
    @DisplayName("PUSH 模式并发消费（concurrency=2）")
    void pushMode_concurrentConsume() {
        MessageHandler<String> handler = msg -> handlerCalls.incrementAndGet();
        startRunner(ConsumerConfig.builder().consumerMode(ConsumerMode.PUSH).concurrency(2).build(), handler);
        for (int i = 0; i < 4; i++) {
            sendMessage("push-" + i, "\"m" + i + "\"");
        }
        await().atMost(15, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(4, handlerCalls.get()));
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(0, pendingCount()));
    }
}
