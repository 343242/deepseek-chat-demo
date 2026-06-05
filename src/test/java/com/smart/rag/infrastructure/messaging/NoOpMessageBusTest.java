package com.smart.rag.infrastructure.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class NoOpMessageBusTest {

    private NoOpMessageBus bus;

    @BeforeEach
    void setUp() {
        bus = new NoOpMessageBus();
    }

    @Test
    void sendReturnsEmptyString() {
        assertEquals("", bus.send(Message.of("test", "payload")));
    }

    @Test
    void sendAsyncReturnsCompletedFuture() {
        CompletableFuture<String> future = bus.sendAsync(Message.of("test", "payload"));
        assertTrue(future.isDone());
        assertEquals("", future.join());
    }

    @Test
    void subscribeReturnsInactiveSubscription() {
        Subscription sub = bus.subscribe("topic", "group",
            ConsumerConfig.DEFAULT, String.class, msg -> {});
        assertFalse(sub.isActive());
        assertEquals("topic", sub.topic());
        assertEquals("group", sub.group());
    }

    @Test
    void subscriptionCloseIsIdempotent() {
        Subscription sub = bus.subscribe("t", "g",
            ConsumerConfig.DEFAULT, String.class, msg -> {});
        assertDoesNotThrow(() -> { sub.close(); sub.close(); });
    }

    @Test
    void shutdownIsNoOp() {
        assertDoesNotThrow(() -> bus.shutdown());
    }

    @Test
    void deadLetterOperationsReturnsEmptyResults() {
        DeadLetterOperations dlq = bus.deadLetterOperations();
        assertNotNull(dlq);
        assertEquals(0, dlq.deadLetterCount("topic"));
        assertEquals(List.of(), dlq.scanDeadLetters("topic", 10));
    }

    @Test
    void managementReturnsExpectedDefaults() {
        assertFalse(bus.isProducerHealthy());
        assertEquals(0, bus.activeSubscriptionCount());
        assertEquals("DISABLED", bus.circuitBreakerState());
    }
}
