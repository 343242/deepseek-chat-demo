package com.smart.rag.infrastructure.messaging;

import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test — connects to already-running Docker Compose RocketMQ at localhost:8081.
 * <p>
 * Prerequisites:
 *   1. docker compose up rmqnamesrv rmqbroker -d
 *   2. bash scripts/init-rocketmq-topics.sh
 * <p>
 * Run: mvn test -Dtest="RocketMQMessageBusSmokeIT" -DskipTests=false
 */
@TestMethodOrder(OrderAnnotation.class)
@DisplayName("RocketMQ 5.x smoke test (docker-compose)")
class RocketMQMessageBusSmokeIT {

    private static RocketMQMessageBus bus;

    @BeforeAll
    static void setUp() {
        String endpoints = System.getenv().getOrDefault("ROCKETMQ_ENDPOINTS", "127.0.0.1:8081");
        MessagingProperties properties = new MessagingProperties(
            "SMART_RAG_", Duration.ofSeconds(30),
            Set.of("rag_index_document"),
            new MessagingProperties.IdempotentConfig(false, 90000),
            new MessagingProperties.CircuitBreakerConfig(5, 30000),
            new MessagingProperties.RocketMQConfig(
                endpoints, "smoke-producer", Duration.ofSeconds(5),
                16, 4194304, false, null, null)
        );
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        bus = new RocketMQMessageBus(properties, new StringCodec(), provider, null, null, null);
    }

    @AfterAll
    static void tearDown() {
        if (bus != null) {
            bus.shutdown();
        }
    }

    // ── PushConsumer ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    @Timeout(30)
    @DisplayName("[Push] sendAsync returns message ID")
    void sendAsyncReturnsMessageId() throws Exception {
        CompletableFuture<String> future = bus.sendAsync(
            MessageEnvelope.of("chat_message_save", "async-payload"));
        String msgId = future.get(15, TimeUnit.SECONDS);
        assertNotNull(msgId);
        assertFalse(msgId.isEmpty());
    }

    @Test
    @Order(2)
    @Timeout(60)
    @DisplayName("[Push] send and receive via PushConsumer")
    void sendAndReceivePushConsumer() throws Exception {
        String topic = "chat_message_save";
        String group = "smoke-push-group";
        String payload = "hello-push-" + System.currentTimeMillis();

        AtomicReference<MessageEnvelope<String>> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Subscription sub = bus.subscribe(topic, group,
            ConsumerConfig.DEFAULT, String.class,
            msg -> {
                if (payload.equals(msg.payload())) {
                    received.set(msg);
                    latch.countDown();
                }
            });

        assertTrue(sub.isActive());

        bus.send(MessageEnvelope.of(topic, payload));

        assertTrue(latch.await(20, TimeUnit.SECONDS), "PushConsumer did not receive message within timeout");
        assertNotNull(received.get());
        assertEquals(payload, received.get().payload());
        assertEquals(topic, received.get().topic());

        sub.close();
        assertFalse(sub.isActive());
    }

    @Test
    @Order(3)
    @Timeout(60)
    @DisplayName("[Push] tag filtering works")
    void tagFiltering() throws Exception {
        String topic = "chat_message_save";
        String group = "smoke-tag-group";

        AtomicReference<MessageEnvelope<String>> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Subscription sub = bus.subscribe(topic, group,
            ConsumerConfig.builder().tagExpression("save").build(),
            String.class,
            msg -> {
                received.set(msg);
                latch.countDown();
            });

        // Send with matching tag
        bus.send(MessageEnvelope.of(topic, "save", "tagged-payload"));

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Tagged message not received");
        assertEquals("tagged-payload", received.get().payload());
        assertEquals("save", received.get().tag());

        sub.close();
    }

    // ── SimpleConsumer ────────────────────────────────────────────────────

    @Test
    @Order(4)
    @Timeout(90)
    @DisplayName("[Simple] send and receive via SimpleConsumer")
    void sendAndReceiveSimpleConsumer() throws Exception {
        String topic = "chat_message_save";
        String group = "smoke-simple-group";
        String payload = "hello-simple-" + System.currentTimeMillis();

        AtomicReference<MessageEnvelope<String>> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ConsumerConfig simpleConfig = ConsumerConfig.builder()
            .consumerMode(ConsumerMode.SIMPLE)
            .batchSize(32)
            .invisibleDuration(Duration.ofMinutes(10))
            .build();

        Subscription sub = bus.subscribe(topic, group,
            simpleConfig, String.class,
            msg -> {
                if (payload.equals(msg.payload())) {
                    received.set(msg);
                    latch.countDown();
                }
            });

        assertTrue(sub.isActive());

        bus.send(MessageEnvelope.ordered(topic, payload, "doc-123"));

        assertTrue(latch.await(30, TimeUnit.SECONDS),
            "SimpleConsumer did not receive message within timeout");
        assertNotNull(received.get());
        assertEquals(payload, received.get().payload());
        assertEquals(topic, received.get().topic());

        sub.close();
    }

    // ── Management + Shutdown ─────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("management methods work")
    void managementMethods() {
        assertTrue(bus.isProducerHealthy());
        assertTrue(bus.activeSubscriptionCount() >= 0);
        assertNotNull(bus.circuitBreakerState());
    }

    @Test
    @Order(Integer.MAX_VALUE)
    @DisplayName("shutdown is clean")
    void shutdown() {
        assertDoesNotThrow(() -> bus.shutdown());
        assertFalse(bus.isProducerHealthy());
    }

    // ── Codec ─────────────────────────────────────────────────────────────

    static class StringCodec implements MessagePayloadCodec {
        @Override
        public byte[] encode(Object payload) {
            return payload.toString().getBytes();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] data, Class<T> type) {
            return (T) new String(data);
        }
    }
}
