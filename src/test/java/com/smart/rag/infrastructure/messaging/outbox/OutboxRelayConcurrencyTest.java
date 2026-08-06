package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.BackoffSchedule;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * OutboxRelay 并发测试（design §3.2）——双 drain 并发 claim，PG {@code FOR UPDATE SKIP LOCKED}
 * 互斥：每行恰好投递一次。重复投递（即时投递与 relay 并发、send 成功 DELETE 失败）由消费端
 * SETNX + DB 唯一约束兜底（at-least-once 文档化权衡，本测试验证的是 SKIP LOCKED 尽力减少）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayConcurrencyTest extends AbstractOutboxTest {

    @Mock
    private MessageBus delegate;

    private MessagePayloadCodec codec;

    @BeforeEach
    void setUp() {
        clearOutbox();
        codec = new com.smart.rag.infrastructure.messaging.JacksonMessageCodec(
            new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private OutboxRelay newRelay() {
        MessagingProperties properties = new MessagingProperties("T_", Duration.ofSeconds(30),
            null, null, null, null, null, null);
        return new OutboxRelay(mapper(), tx(), delegate, codec,
            new SharedCircuitBreakerGate(null, null, properties) {
                @Override
                public boolean isOpen(String topic) { return false; }
            },
            new BackoffSchedule(null), properties,
            new RedissonLeadership(null, "k", Duration.ofSeconds(5)),
            new OutboxMetrics(new SimpleMeterRegistry()), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("双 drain 并发 claim：SKIP LOCKED 互斥，每行恰好投递一次")
    void concurrentClaimsDeliverEachRowOnce() throws Exception {
        int rows = 60;
        for (int i = 0; i < rows; i++) {
            insertPending("chat_message_save", 0);
        }

        List<String> deliveredIds = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger sendCalls = new AtomicInteger();
        // send 延迟模拟网络 IO，扩大并发窗口
        doAnswer(inv -> {
            sendCalls.incrementAndGet();
            Thread.sleep(5);
            deliveredIds.add(String.valueOf(sendCalls.get()));
            return "id";
        }).when(delegate).send(any());

        OutboxRelay relayA = newRelay();
        OutboxRelay relayB = newRelay();
        CountDownLatch bothDone = new CountDownLatch(2);
        Thread tA = new Thread(() -> { relayA.drainUntilEmpty(); bothDone.countDown(); });
        Thread tB = new Thread(() -> { relayB.drainUntilEmpty(); bothDone.countDown(); });
        tA.start();
        tB.start();
        assertThat(bothDone.await(60, TimeUnit.SECONDS)).isTrue();

        // 每行恰好投递一次：行全部删除，投递次数 = 行数（SKIP LOCKED 防并发 claim 同一行）
        assertThat(countRows(null)).isZero();
        assertThat(sendCalls.get()).isEqualTo(rows);
    }
}
