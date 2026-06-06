package com.smart.rag.infrastructure.messaging;

import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
@DisplayName("RocketMQ 5.x integration tests")
@Disabled("Requires Docker with stable RocketMQ proxy networking. Run manually against docker-compose.")
class RocketMQMessageBusIT {

    private static final String ROCKETMQ_IMAGE = "apache/rocketmq:5.2.0";
    private static final int PROXY_PORT = 8081;
    private static final String TOPIC_PREFIX = "SMART_RAG_";

    private static final Network network = Network.newNetwork();

    @Container
    private static final GenericContainer<?> namesrv = new GenericContainer<>(DockerImageName.parse(ROCKETMQ_IMAGE))
        .withNetwork(network)
        .withNetworkAliases("namesrv")
        .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx256m")
        .withCommand("sh", "mqnamesrv")
        .waitingFor(Wait.forLogMessage(".*success.*", 1)
            .withStartupTimeout(Duration.ofMinutes(5)));

    @Container
    private static final GenericContainer<?> broker = new GenericContainer<>(DockerImageName.parse(ROCKETMQ_IMAGE))
        .withNetwork(network)
        .withNetworkAliases("broker")
        .withEnv("NAMESRV_ADDR", "namesrv:9876")
        .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m")
        .withCommand("sh", "mqbroker", "-n", "namesrv:9876", "--enable-proxy")
        .withExposedPorts(PROXY_PORT)
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)));

    private static RocketMQMessageBus bus;

    @BeforeAll
    static void setUp() {
        String endpoints = "localhost:" + broker.getMappedPort(PROXY_PORT);
        MessagingProperties properties = new MessagingProperties(
            TOPIC_PREFIX, Duration.ofSeconds(30),
            Set.of("ordered_topic"),
            new MessagingProperties.IdempotentConfig(false, 90000),
            new MessagingProperties.CircuitBreakerConfig(5, 30000),
            new MessagingProperties.RocketMQConfig(
                endpoints, "test-producer", Duration.ofSeconds(5),
                16, 4194304, null, null)
        );
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        bus = new RocketMQMessageBus(properties, new JacksonMessageCodecTestSupport(), provider);
    }

    @Test
    @Order(1)
    @Timeout(30)
    @DisplayName("sendAsync returns CompletableFuture with message ID")
    void sendAsyncReturnsMessageId() throws Exception {
        String topic = "it_async_test";
        String payload = "async-payload";

        CompletableFuture<String> future = bus.sendAsync(Message.of(topic, payload));
        String msgId = future.get(15, TimeUnit.SECONDS);

        assertNotNull(msgId);
        assertFalse(msgId.isEmpty());
    }

    @Test
    @Order(2)
    @Timeout(60)
    @DisplayName("send and receive via PushConsumer")
    void sendAndReceivePushConsumer() throws Exception {
        String topic = "it_push_test";
        String group = "it-push-group";
        String payload = "hello-push-" + System.currentTimeMillis();

        AtomicReference<Message<String>> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Subscription subscription = bus.subscribe(topic, group,
            ConsumerConfig.DEFAULT, String.class,
            msg -> {
                received.set(msg);
                latch.countDown();
            });

        assertTrue(subscription.isActive());

        bus.send(Message.of(topic, payload));

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Message not received within timeout");
        assertNotNull(received.get());
        assertEquals(payload, received.get().payload());
        assertEquals(topic, received.get().topic());

        subscription.close();
        assertFalse(subscription.isActive());
    }

    @Test
    @Order(3)
    @Timeout(60)
    @DisplayName("send with tag and ordered message")
    void sendWithTagAndOrdering() throws Exception {
        String topic = "ordered_topic";
        String group = "it-ordered-group";
        String payload = "ordered-" + System.currentTimeMillis();

        AtomicReference<Message<String>> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Subscription subscription = bus.subscribe(topic, group,
            ConsumerConfig.builder().tagExpression("save").build(),
            String.class,
            msg -> {
                received.set(msg);
                latch.countDown();
            });

        bus.send(Message.of(topic, "save", payload));

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Tagged message not received");
        assertEquals(payload, received.get().payload());
        assertEquals("save", received.get().tag());

        subscription.close();
    }

    @Test
    @Order(4)
    @DisplayName("management methods work")
    void managementMethods() {
        assertTrue(bus.isProducerHealthy());
        assertTrue(bus.activeSubscriptionCount() >= 0);
    }

    @Test
    @Order(Integer.MAX_VALUE)
    @DisplayName("shutdown is clean")
    void shutdown() {
        assertDoesNotThrow(() -> bus.shutdown());
        assertFalse(bus.isProducerHealthy());
    }

    /** Minimal codec for tests — String as UTF-8 bytes */
    static class JacksonMessageCodecTestSupport implements MessagePayloadCodec {
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
