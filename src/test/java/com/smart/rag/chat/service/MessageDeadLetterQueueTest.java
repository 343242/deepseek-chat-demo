package com.smart.rag.chat.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;

import java.util.LinkedList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MessageDeadLetterQueue}.
 * <p>
 * Covers the Phase C Step 3 observability additions:
 * <ul>
 *   <li>{@code size()} is non-destructive and null-safe.</li>
 *   <li>{@code legacy.dlq.size} gauge registers when a {@link MeterRegistry} is present
 *       and is a no-op when absent.</li>
 * </ul>
 */
class MessageDeadLetterQueueTest {

    @Test
    void size_returnsRQueueSizeWhenRedissonPresent() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RQueue<DeadLetterEntry> queue = mock(RQueue.class);
        when(redisson.<DeadLetterEntry>getQueue(DeadLetterEntry.QUEUE_KEY)).thenReturn(queue);
        when(queue.size()).thenReturn(7);

        MessageDeadLetterQueue dlq = new MessageDeadLetterQueue(redisson, null);

        assertThat(dlq.size()).isEqualTo(7L);
    }

    @Test
    void size_returnsZeroWhenRedissonNull() {
        MessageDeadLetterQueue dlq = new MessageDeadLetterQueue(null, null);

        assertThat(dlq.size()).isZero();
    }

    @Test
    void size_returnsZeroOnRedissonException() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<DeadLetterEntry>getQueue(DeadLetterEntry.QUEUE_KEY))
            .thenThrow(new RuntimeException("redis down"));

        MessageDeadLetterQueue dlq = new MessageDeadLetterQueue(redisson, null);

        assertThat(dlq.size()).isZero();
    }

    @Test
    void size_isNonDestructive() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RQueue<DeadLetterEntry> queue = mock(RQueue.class);
        when(redisson.<DeadLetterEntry>getQueue(DeadLetterEntry.QUEUE_KEY)).thenReturn(queue);
        when(queue.size()).thenReturn(5);

        MessageDeadLetterQueue dlq = new MessageDeadLetterQueue(redisson, null);

        // Repeated calls should not drain the queue (drain() polls; size() must not)
        assertThat(dlq.size()).isEqualTo(5L);
        assertThat(dlq.size()).isEqualTo(5L);
        assertThat(dlq.size()).isEqualTo(5L);
    }

    @Test
    void gauge_registersWhenMeterRegistryPresent() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RQueue<DeadLetterEntry> queue = mock(RQueue.class);
        when(redisson.<DeadLetterEntry>getQueue(DeadLetterEntry.QUEUE_KEY)).thenReturn(queue);
        when(queue.size()).thenReturn(3);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        // gauge is registered as a constructor side effect; assertions query the registry below
        new MessageDeadLetterQueue(redisson, registry);

        assertThat(registry.find(MessageDeadLetterQueue.SIZE_GAUGE_NAME).gauge())
            .as("legacy.dlq.size gauge should be registered")
            .isNotNull();
        assertThat(registry.get(MessageDeadLetterQueue.SIZE_GAUGE_NAME).gauge().value())
            .isEqualTo(3.0);
    }

    @Test
    void gauge_updatesLiveAsSizeChanges() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RQueue<DeadLetterEntry> queue = mock(RQueue.class);
        when(redisson.<DeadLetterEntry>getQueue(DeadLetterEntry.QUEUE_KEY)).thenReturn(queue);
        // Use a mutable counter to simulate live queue depth changes
        java.util.concurrent.atomic.AtomicInteger depth = new java.util.concurrent.atomic.AtomicInteger(1);
        when(queue.size()).thenAnswer(inv -> depth.get());

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MessageDeadLetterQueue(redisson, registry);

        assertThat(registry.get(MessageDeadLetterQueue.SIZE_GAUGE_NAME).gauge().value())
            .isEqualTo(1.0);
        depth.set(9);
        assertThat(registry.get(MessageDeadLetterQueue.SIZE_GAUGE_NAME).gauge().value())
            .isEqualTo(9.0);
    }

    @Test
    void gauge_isNoOpWhenMeterRegistryNull() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RQueue<DeadLetterEntry> queue = mock(RQueue.class);
        when(redisson.<DeadLetterEntry>getQueue(DeadLetterEntry.QUEUE_KEY)).thenReturn(queue);
        when(queue.size()).thenReturn(2);

        // No exception; size() still works
        MessageDeadLetterQueue dlq = new MessageDeadLetterQueue(redisson, null);

        assertThat(dlq.size()).isEqualTo(2L);
    }

    @Test
    void gauge_reportsZeroWhenRedissonNull() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        MessageDeadLetterQueue dlq = new MessageDeadLetterQueue(null, registry);

        assertThat(dlq.size()).isZero();
        assertThat(registry.get(MessageDeadLetterQueue.SIZE_GAUGE_NAME).gauge().value())
            .isEqualTo(0.0);
    }

    @Test
    void enqueueAndDrain_preserveExistingBehavior() {
        // Regression: ensure size() addition did not disturb enqueue/drain contract
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RQueue<DeadLetterEntry> queue = mock(RQueue.class);
        java.util.Queue<DeadLetterEntry> backing = new LinkedList<>();
        when(redisson.<DeadLetterEntry>getQueue(DeadLetterEntry.QUEUE_KEY)).thenReturn(queue);
        when(queue.offer(any())).thenAnswer(inv -> backing.offer(inv.getArgument(0)));
        when(queue.poll()).thenAnswer(inv -> backing.poll());
        when(queue.size()).thenAnswer(inv -> backing.size());

        MessageDeadLetterQueue dlq = new MessageDeadLetterQueue(redisson, null);

        DeadLetterEntry e1 = new DeadLetterEntry("c1", "u1", "a1", "m1", 10, 100L);
        DeadLetterEntry e2 = new DeadLetterEntry("c2", "u2", "a2", "m2", 20, 200L);
        dlq.enqueue(e1);
        dlq.enqueue(e2);

        assertThat(dlq.size()).isEqualTo(2L);
        assertThat(dlq.drain(1)).hasSize(1);
        assertThat(dlq.size()).isEqualTo(1L);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
