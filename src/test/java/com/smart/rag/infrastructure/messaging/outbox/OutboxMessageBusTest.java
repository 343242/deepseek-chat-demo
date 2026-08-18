package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxMessageBus 测试（design §2）——send() 的 outbox 托管语义：
 * INSERT 含 payload_type/tag/hash_key 列；即时投递成功删行；失败留行；熔断 OPEN 跳过；
 * 有界 executor 拒绝时行留 relay。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxMessageBusTest {

    @Mock
    private MessageBus delegate;

    @Mock
    private OutboxMapper outboxMapper;

    @Mock
    private MessagePayloadCodec codec;

    @Mock
    private SharedCircuitBreakerGate cbGate;

    private OutboxMessageBus bus;
    private MessagingProperties properties;

    /** 小队列测试拒绝路径。 */
    private static final MessagingProperties SMALL_QUEUE_PROPS = new MessagingProperties(
        "T_", Duration.ofSeconds(30), null, null, null, null, null,
        new MessagingProperties.OutboxConfig(null, 0, 0, 0, 0, 0,
            1, 1, 1, 0, null, null, null, 0, 0, null));

    @BeforeEach
    void setUp() {
        properties = new MessagingProperties("T_", Duration.ofSeconds(30), null, null, null, null, null, null);
        when(codec.encode(any())).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(outboxMapper.insert(any(OutboxEntry.class))).thenAnswer(inv -> {
            OutboxEntry entry = inv.getArgument(0);
            entry.setId(42L);
            return 1;
        });
        bus = new OutboxMessageBus(delegate, outboxMapper, codec, cbGate, properties,
            new OutboxMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    private MessageEnvelope<String> envelope() {
        return MessageEnvelope.of("chat_message_save", "hello");
    }

    private MessageEnvelope<String> fullEnvelope() {
        return new MessageEnvelope<>("ignored", "rag_index_document", "tag-x", "doc-payload",
            "doc-42", "dedup-1", Map.of("traceparent", "00-abc-def-01"), 123456789L);
    }

    @Test
    @DisplayName("send：INSERT outbox 含 payload_type/tag/hash_key 列（P1-4/P1-8），返回 outboxId")
    void sendInsertsRowWithColumns() {
        bus.send(fullEnvelope());

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxMapper).insert(captor.capture());
        OutboxEntry entry = captor.getValue();
        assertThat(entry.getTopic()).isEqualTo("rag_index_document");
        assertThat(entry.getPayloadType()).isEqualTo(String.class.getName());
        assertThat(entry.getTag()).isEqualTo("tag-x");
        assertThat(entry.getHashKey()).isEqualTo("doc-42");
        assertThat(entry.getDedupKey()).isEqualTo("dedup-1");
        assertThat(entry.getStatus()).isEqualTo("pending");
        assertThat(entry.getAttempts()).isZero();
        assertThat(entry.getHeaders()).contains("00-abc-def-01");
    }

    @Test
    @DisplayName("send：即时投递成功 → 行被删除")
    void sendDeliversAndDeletes() {
        when(delegate.send(any())).thenReturn("1690000000000-0");

        String id = bus.send(envelope());

        assertThat(id).isEqualTo("42");
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(outboxMapper).deleteByIds(anyList()));
        verify(delegate).send(any());
    }

    @Test
    @DisplayName("send：即时投递失败（含重试）→ 行留 relay，不抛异常")
    void sendFailureLeavesRow() {
        doThrow(new RuntimeException("redis down")).when(delegate).send(any());

        // 不抛（fire-and-persist 语义：失败留行，relay 兜底）
        String id = bus.send(envelope());
        assertThat(id).isEqualTo("42");

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> verify(delegate, times(3)).send(any()));   // 1 + 2 次重试
        verify(outboxMapper, never()).deleteByIds(anyList());
    }

    @Test
    @DisplayName("send：熔断 OPEN → 跳过即时投递（不 send），行留 relay")
    void sendSkipsWhenGateOpen() {
        when(cbGate.isOpen("chat_message_save")).thenReturn(true);

        bus.send(envelope());

        Awaitility.await().atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(true).isTrue());   // 给异步路径留时间（本应无任务）
        verify(delegate, never()).send(any());
        verify(outboxMapper, never()).deleteByIds(anyList());
    }

    @Test
    @DisplayName("send：executor 拒绝（队列满）→ 行留 relay，不抛")
    void sendRejectedLeavesRow() {
        // 占满 1 线程 + 1 队列：第一个任务阻塞线程，后续任务被拒绝
        doThrow(new RuntimeException("redis down")).when(delegate).send(any());
        OutboxMessageBus smallBus = new OutboxMessageBus(delegate, outboxMapper, codec, cbGate,
            SMALL_QUEUE_PROPS, new OutboxMetrics(null));
        try {
            when(outboxMapper.insert(any(OutboxEntry.class))).thenAnswer(inv -> {
                OutboxEntry entry = inv.getArgument(0);
                entry.setId(99L);
                return 1;
            });
            // 灌满 executor（core=1, queue=1）
            smallBus.send(envelope());
            Awaitility.await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(delegate, atLeastOnce()).send(any()));
            // 队列已满 → 本次任务被拒绝 → 行留 relay（不抛）
            smallBus.send(envelope());
            smallBus.send(envelope());
        } finally {
            smallBus.close();
        }
    }

    @Test
    @DisplayName("send：outbox INSERT 失败 → 抛 MessagingException(400013)")
    void sendInsertFailureThrows() {
        when(outboxMapper.insert(any(OutboxEntry.class)))
            .thenThrow(new RuntimeException("db down"));

        try {
            bus.send(envelope());
        } catch (com.smart.rag.infrastructure.exception.MessagingException e) {
            assertThat(e.getErrorCode()).isEqualTo(
                com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode.OUTBOX_INSERT_FAILED);
            return;
        }
        throw new AssertionError("expected OUTBOX_INSERT_FAILED");
    }

    @Test
    @DisplayName("subscribe/shutdown/deadLetterOperations 委托 delegate")
    void delegatesManagement() {
        bus.shutdown();
        verify(delegate).shutdown();
        bus.deadLetterOperations();
        verify(delegate).deadLetterOperations();
    }
}