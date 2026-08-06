package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxMessageBus.sendAsync 测试（design §6.1）——sendAsync 同样经 outbox 托管（非裸委托，
 * 避免静默绕过持久化）：future 表示即时投递 best-effort 结果。
 */
@ExtendWith(MockitoExtension.class)
class OutboxMessageBusSendAsyncTest {

    @Mock
    private MessageBus delegate;

    @Mock
    private OutboxMapper outboxMapper;

    @Mock
    private MessagePayloadCodec codec;

    @Mock
    private SharedCircuitBreakerGate cbGate;

    private OutboxMessageBus bus;

    @BeforeEach
    void setUp() {
        MessagingProperties properties = new MessagingProperties("T_", Duration.ofSeconds(30),
            null, null, null, null, null, null);
        when(codec.encode(any())).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(outboxMapper.insert(any(OutboxEntry.class))).thenAnswer(inv -> {
            OutboxEntry entry = inv.getArgument(0);
            entry.setId(7L);
            return 1;
        });
        bus = new OutboxMessageBus(delegate, outboxMapper, codec, cbGate, properties,
            new OutboxMetrics(null));
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    private MessageEnvelope<String> envelope() {
        return MessageEnvelope.of("chat_usage_record", "usage");
    }

    @Test
    @DisplayName("sendAsync 经 outbox：INSERT + 即时投递成功 → future complete(delegateId) + 删行")
    void successCompletesWithDelegateId() throws Exception {
        when(delegate.send(any())).thenReturn("1690000000000-0");

        CompletableFuture<String> future = bus.sendAsync(envelope());

        assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("1690000000000-0");
        verify(outboxMapper).insert(any(OutboxEntry.class));
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(outboxMapper).deleteByIds(anyList()));
    }

    @Test
    @DisplayName("sendAsync 即时重试耗尽 → future completeExceptionally，但行留 relay（不丢）")
    void exhaustedCompletesExceptionallyRowStays() {
        doThrow(new RuntimeException("redis down")).when(delegate).send(any());

        CompletableFuture<String> future = bus.sendAsync(envelope());

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(future).isCompletedExceptionally());
        verify(delegate, times(3)).send(any());   // 1 + 2 次重试
        verify(outboxMapper, never()).deleteByIds(anyList());
    }

    @Test
    @DisplayName("sendAsync 熔断 OPEN → future 立即 complete(outboxId)，行留 relay")
    void gateOpenCompletesImmediately() {
        when(cbGate.isOpen("chat_usage_record")).thenReturn(true);

        CompletableFuture<String> future = bus.sendAsync(envelope());

        assertThat(future).isCompleted();
        assertThat(future.join()).isEqualTo("7");
        verify(delegate, never()).send(any());
        verify(outboxMapper, never()).deleteByIds(anyList());
    }

    @Test
    @DisplayName("sendAsync 幂等：仍 INSERT outbox 行（含列）")
    void stillInsertsRow() {
        when(delegate.send(any())).thenReturn("id");

        bus.sendAsync(envelope());

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(delegate).send(any()));
        verify(outboxMapper).insert(any(OutboxEntry.class));
    }
}
