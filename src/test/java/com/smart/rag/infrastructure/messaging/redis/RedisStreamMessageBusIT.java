package com.smart.rag.infrastructure.messaging.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.PermanentConsumeException;
import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.JacksonMessageCodec;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.Subscription;
import com.smart.rag.infrastructure.messaging.TracePropagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RedisStreamMessageBus} 端到端（Testcontainers）：send → subscribe → consume；
 * IdempotentHandler SETNX 包装透明复用；deadLetterOperations 真正实现；subscribe 幂等返回同一实例。
 */
class RedisStreamMessageBusIT extends AbstractRedisStreamTest {

    private static final String TOPIC = "chat_message_save";
    private static final String GROUP = "save-group";

    private RedisStreamMessageBus bus;
    private Subscription subscription;
    private RedisStreamConsumerConnections perTestConnections;
    private final AtomicInteger handlerCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        flushAll();
        perTestConnections = newConnections();
        bus = new RedisStreamMessageBus(props(), business(), new JacksonMessageCodec(new ObjectMapper()),
            null, TracePropagator.NO_OP, null, perTestConnections, keys(), dlqWriter(),
            new RetrySweeper(delayQueue(), keys(), backoff(), props(), metrics(), dlqWriter()),
            new PelRecoverySweeper(perTestConnections, props(), metrics()),
            new StreamTrimTask(perTestConnections, props(), metrics()));
    }

    @AfterEach
    void tearDown() {
        if (subscription != null) {
            subscription.close();
        }
        bus.shutdown();
    }

    private ConsumerConfig lightConfig() {
        return ConsumerConfig.builder().concurrency(1).build();
    }

    @Test
    @DisplayName("send → subscribe → handler：headers 还原、dedupKey 透传")
    void endToEnd_sendSubscribeConsume() {
        subscription = bus.subscribe(TOPIC, GROUP, lightConfig(), String.class, msg -> {
            assertEquals("original-trace", msg.headers().get("traceparent"));
            assertEquals("dedup-1", msg.deduplicationKey());
            handlerCalls.incrementAndGet();
        });

        MessageEnvelope<String> envelope = new MessageEnvelope<>(null, TOPIC, null, "hello",
            null, "dedup-1", Map.of("traceparent", "original-trace"), System.currentTimeMillis());
        String id = bus.send(envelope);
        assertNotNull(id);
        assertTrue(id.contains("-"), "entry ID 应形如 ms-seq: " + id);

        await().atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(1, handlerCalls.get()));
    }

    @Test
    @DisplayName("IdempotentHandler SETNX 包装对 bus 透明：同 dedupKey 消息只处理一次")
    void idempotentHandler_deduplicates() throws InterruptedException {
        subscription = bus.subscribe(TOPIC, GROUP, lightConfig(), String.class,
            msg -> handlerCalls.incrementAndGet());

        bus.send(MessageEnvelope.deduplicated(TOPIC, "p1", "dup-key-1"));
        bus.send(MessageEnvelope.deduplicated(TOPIC, "p2", "dup-key-1"));   // 重复

        await().atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(1, handlerCalls.get()));
        // 等待足够时间确认第二条未被处理（去重窗口内）
        Thread.sleep(500);
        assertEquals(1, handlerCalls.get());
    }

    @Test
    @DisplayName("Permanent 失败经 bus 进 DLQ；deadLetterOperations 三方法可用（非 UNSUPPORTED 桩）")
    void permanentFailure_busDlqAndOps() throws InterruptedException {
        subscription = bus.subscribe(TOPIC, GROUP, lightConfig(), String.class, msg -> {
            throw new PermanentConsumeException("poison");
        });
        bus.send(MessageEnvelope.deduplicated(TOPIC, "p", "poison-1"));

        await().atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(1, bus.deadLetterOperations().deadLetterCount(TOPIC)));
        assertEquals(1, bus.deadLetterOperations().scanDeadLetters(TOPIC, 10).size());

        // replay 回灌主 stream → 重新投递（幂等键已删除：Permanent 路径 IdempotentHandler 不删键……
        // 这里 handler 永远抛 Permanent，replay 后再次进 DLQ）
        String dlqId = bus.deadLetterOperations().scanDeadLetters(TOPIC, 1).getFirst().id();
        bus.deadLetterOperations().replayDeadLetter(TOPIC, dlqId);
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertTrue(bus.deadLetterOperations().deadLetterCount(TOPIC) >= 2));
    }

    @Test
    @DisplayName("subscribe 同 topic+group 幂等返回同一实例；close 幂等")
    void subscribeIdempotent_andCloseIdempotent() {
        subscription = bus.subscribe(TOPIC, GROUP, lightConfig(), String.class, msg -> { });
        Subscription second = bus.subscribe(TOPIC, GROUP, lightConfig(), String.class, msg -> { });

        assertSame(subscription, second);
        subscription.close();
        assertDoesNotThrow(() -> subscription.close());
        subscription = null;
    }

    @Test
    @DisplayName("isCircuitBreakerOpen 初始 false；circuitBreakerState 空")
    void breakerState_initial() {
        assertFalse(bus.isCircuitBreakerOpen(TOPIC));
        assertTrue(bus.circuitBreakerState().isEmpty());
        assertTrue(bus.isProducerHealthy());   // Redis 可达
    }

    @Test
    @DisplayName("send 消息 hashKey 随字段写入（bus 不分区，供业务层参考，R6）")
    void hashKeyWrittenAsField() {
        subscription = bus.subscribe(TOPIC, GROUP, lightConfig(), String.class, msg -> handlerCalls.incrementAndGet());
        bus.send(MessageEnvelope.ordered(TOPIC, "p", "doc-42"));

        await().atMost(15, TimeUnit.SECONDS)
            .untilAsserted(() -> assertEquals(1, handlerCalls.get()));

        var records = business().opsForStream().range(keys().streamKey(TOPIC),
            org.springframework.data.domain.Range.unbounded());
        assertEquals("doc-42", records.getFirst().getValue().get("hashKey"));
    }
}
