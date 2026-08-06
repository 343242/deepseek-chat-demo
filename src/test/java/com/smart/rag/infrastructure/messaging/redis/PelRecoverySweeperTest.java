package com.smart.rag.infrastructure.messaging.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.ConsumerMode;
import com.smart.rag.infrastructure.messaging.JacksonMessageCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.TracePropagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link PelRecoverySweeper} 单测（R4/P1-6，Testcontainers，短 minIdle 加速）：
 * 模拟 consumer 崩溃（已 XREADGROUP 未 XACK）→ XAUTOCLAIM 回收 → 异步派发到 processingPool
 * → 正常 handle + XACK。PEL 清空；未过 minIdle 的消息不被回收。
 */
class PelRecoverySweeperTest extends AbstractRedisStreamTest {

    private static final String TOPIC = "rag_index_document";
    private static final String GROUP = "index-group";
    private static final long PEL_MIN_IDLE_MS = 1000;

    private final AtomicInteger handlerCalls = new AtomicInteger();
    private RedisStreamConsumerRunner<String> runner;
    private PelRecoverySweeper sweeper;

    @BeforeEach
    void setUp() {
        flushAll();
        MessagingProperties pelProps = customProps(new MessagingProperties.RedisStreamConfig(
            null, null, null, null, null, 0, 0, null, 0, Duration.ofMillis(PEL_MIN_IDLE_MS),
            null, null, 0, null, null, null));
        sweeper = new PelRecoverySweeper(connections(), pelProps, metrics());
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.stop();
        }
    }

    private void quietCreateGroup() {
        try {
            connections().executeCommand("XGROUP", "CREATE", keys().streamKey(TOPIC), GROUP, "$", "MKSTREAM");
        } catch (Exception e) {
            // BUSYGROUP tolerated
        }
    }

    private void addMessage(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", TOPIC);
        fields.put("tag", "");
        fields.put("dedupKey", payload);
        fields.put("hashKey", "doc-" + payload);
        fields.put("headers", "{}");
        fields.put("payload", "\"" + payload + "\"");
        fields.put("bornTs", "1");
        fields.put("attempt", "0");
        fields.put("contentType", "application/json");
        business().opsForStream().add(MapRecord.create(keys().streamKey(TOPIC), fields));
    }

    private RedisStreamConsumerRunner<String> startRunner() {
        runner = new RedisStreamConsumerRunner<>(TOPIC, GROUP, "app:pel-test",
            ConsumerConfig.builder().consumerMode(ConsumerMode.SIMPLE).concurrency(1).build(),
            String.class, msg -> handlerCalls.incrementAndGet(),
            new JacksonMessageCodec(new ObjectMapper()), TracePropagator.NO_OP,
            metrics(), props(), connections(),
            new RetrySweeper(delayQueue(), keys(), backoff(), props(), metrics(), dlqWriter()),
            dlqWriter());
        runner.start();
        return runner;
    }

    @Test
    @DisplayName("未 XACK 消息在 pelMinIdle 后 XAUTOCLAIM 回收并异步处理（P1-6），PEL 清空")
    void claimsAndProcessesAbandonedMessages() throws Exception {
        String streamKey = keys().streamKey(TOPIC);
        quietCreateGroup();
        addMessage("m1");
        addMessage("m2");
        // dead consumer 读取但不 XACK → 2 条留 PEL（此时 runner 尚未启动，无竞争）
        List<MapRecord<String, Object, Object>> read = connections().streamOps().read(
            Consumer.from(GROUP, "app:dead-consumer"),
            StreamReadOptions.empty().count(10).block(Duration.ofMillis(200)),
            StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        assertEquals(2, read.size());
        assertEquals(2, connections().streamOps().pending(streamKey, GROUP).getTotalPendingMessages());

        // 崩溃恢复后新 runner 启动并注册
        RedisStreamConsumerRunner<String> recoveryRunner = startRunner();
        sweeper.register(recoveryRunner);

        // 过 minIdle 后 sweep → XAUTOCLAIM 转移归属 → 异步派发到 processingPool → handle → XACK
        Thread.sleep(PEL_MIN_IDLE_MS + 200);
        sweeper.drain();

        await().atMost(15, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(2, handlerCalls.get()));
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(0, connections().streamOps()
                .pending(streamKey, GROUP).getTotalPendingMessages()));
    }

    @Test
    @DisplayName("刚投递（未过 minIdle）的消息不被 XAUTOCLAIM 回收")
    void freshMessages_notClaimed() {
        String streamKey = keys().streamKey(TOPIC);
        // minIdle 10s：mock 装配耗时（最坏 ~2s）远低于阈值，保证"未过 minIdle"确定性
        PelRecoverySweeper longMinIdleSweeper = new PelRecoverySweeper(connections(), customProps(
            new MessagingProperties.RedisStreamConfig(null, null, null, null, null, 0, 0, null, 0,
                Duration.ofSeconds(10), null, null, 0, null, null, null)), metrics());
        quietCreateGroup();
        addMessage("fresh");
        connections().streamOps().read(
            Consumer.from(GROUP, "app:app-consumer"),
            StreamReadOptions.empty().count(10).block(Duration.ofMillis(200)),
            StreamOffset.create(streamKey, ReadOffset.lastConsumed()));

        // mock runner 注册（仅用于驱动 sweep；未过 minIdle 时不应派发任何消息）
        RedisStreamConsumerRunner<?> mockRunner = mock(RedisStreamConsumerRunner.class);
        when(mockRunner.topic()).thenReturn(TOPIC);
        when(mockRunner.group()).thenReturn(GROUP);
        longMinIdleSweeper.register(mockRunner);

        longMinIdleSweeper.drain();

        assertEquals(1, connections().streamOps().pending(streamKey, GROUP).getTotalPendingMessages());
        verify(mockRunner, never()).dispatchToProcessingPool(any(Runnable.class));
    }
}
