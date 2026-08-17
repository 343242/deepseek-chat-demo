package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.BackoffSchedule;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxRelay 测试（design §3.2，真实 PG + mock delegate/gate）——
 * claim 短事务 / 事务外 send / 批量 DELETE；退避 bumpAttempts；maxAttempts→dead；
 * drain 异常隔离（P0-3）；drain-until-empty；gate OPEN 冻结 attempts（P1-7）；claiming 超时回收。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTest extends AbstractOutboxTest {

    @Mock
    private MessageBus delegate;

    @Mock
    private SharedCircuitBreakerGate cbGate;

    private MessagePayloadCodec codec;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        clearOutbox();
        codec = new com.smart.rag.infrastructure.messaging.JacksonMessageCodec(
            new com.fasterxml.jackson.databind.ObjectMapper());
        when(cbGate.isOpen(any())).thenReturn(false);
        MessagingProperties properties = new MessagingProperties("T_", Duration.ofSeconds(30),
            null, null, null, null, null, null);
        relay = new OutboxRelay(mapper(), tx(), delegate, codec, cbGate,
            new BackoffSchedule(null), properties,
            new RedissonLeadership(null, "k", Duration.ofSeconds(5)),   // null redisson → leader=true
            new OutboxMetrics(new SimpleMeterRegistry()), new SimpleMeterRegistry());
        when(delegate.send(any())).thenReturn("1690000000000-0");
    }

    @AfterEach
    void tearDown() {
        relay.stop();
    }

    @Test
    @DisplayName("pending 到期行：claim → 事务外 send → 批量 DELETE")
    void drainsPendingAndDeletes() {
        OutboxEntry row = insertPending("chat_message_save", 0);

        relay.drainUntilEmpty();

        verify(delegate).send(any());
        assertThat(countRows(null)).isZero();
        assertThat(reload(row.getId())).isNull();
    }

    @Test
    @DisplayName("未到期行（next_retry_at 未来）不被 claim")
    void notYetDueNotClaimed() {
        OutboxEntry row = new OutboxEntry();
        row.setTopic("chat_message_save");
        row.setPayload("\"x\"");
        row.setPayloadType(String.class.getName());
        row.setHeaders("{}");
        row.setStatus("pending");
        row.setAttempts(0);
        row.setNextRetryAt(Instant.now().plusSeconds(3600));
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        mapper().insert(row);

        relay.drainUntilEmpty();

        verify(delegate, times(0)).send(any());
        assertThat(countRows("pending")).isEqualTo(1);
    }

    @Test
    @DisplayName("真实投递失败 → bumpAttempts + 退避 next_retry_at（B 类瞬态重试）")
    void failureBumpsAttemptsWithBackoff() {
        OutboxEntry row = insertPending("chat_message_save", 0);
        doThrow(new RuntimeException("redis down")).when(delegate).send(any());

        relay.drainUntilEmpty();

        OutboxEntry after = reload(row.getId());
        assertThat(after.getStatus()).isEqualTo("pending");
        assertThat(after.getAttempts()).isEqualTo(1);
        // 退避第 1 档 = backoff.next(1) = 1000ms；next_retry_at 在 now+1s 前后
        assertThat(after.getNextRetryAt())
            .isBetween(Instant.now().plusMillis(500), Instant.now().plusMillis(5000));
    }

    @Test
    @DisplayName("attempts 耗尽（>= maxAttempts）→ dead + failure_reason + 不再留 pending")
    void attemptsExhaustedMarksDead() {
        // maxAttempts 默认 16；直接插 15 次尝试的行 → 本次失败后 16 >= 16 → dead
        OutboxEntry row = insertPending("chat_message_save", 15);
        doThrow(new RuntimeException("poison message")).when(delegate).send(any());

        relay.drainUntilEmpty();

        OutboxEntry after = reload(row.getId());
        assertThat(after.getStatus()).isEqualTo("dead");
        assertThat(after.getFailureReason()).contains("poison message");
    }

    @Test
    @DisplayName("P1-7：gate OPEN → 不 send、不递增 attempts，仅顺延 next_retry_at")
    void gateOpenFreezesAttempts() {
        OutboxEntry row = insertPending("chat_message_save", 7);
        when(cbGate.isOpen("chat_message_save")).thenReturn(true);

        relay.drainUntilEmpty();

        verify(delegate, times(0)).send(any());
        OutboxEntry after = reload(row.getId());
        assertThat(after.getStatus()).isEqualTo("pending");
        assertThat(after.getAttempts()).isEqualTo(7);   // 冻结：不递增
        assertThat(after.getNextRetryAt()).isAfter(Instant.now());   // 顺延 gateDeferInterval（5s 默认）
    }

    @Test
    @DisplayName("P0-3：drain 抛异常 → tryDrainIfLeader 吞掉，后续调度继续")
    void drainExceptionDoesNotKillScheduling() throws java.sql.SQLException {
        insertPending("chat_message_save", 0);
        doThrow(new RuntimeException("db connection lost"))
            .when(delegate).send(any());
        relay.start();   // leadership.start() → leader=true（tryDrainIfLeader 门控）

        // 第一次 drain：send 抛异常 → bump 失败（mock 无异常）——用 claim 抛异常模拟：
        // 构造一个 delegate 抛、且 mapper 正常；真正验证 P0-3 是 tryDrainIfLeader 不传播
        try {
            relay.tryDrainIfLeader();   // 不抛
        } catch (Throwable t) {
            throw new AssertionError("P0-3 violated: drain exception propagated", t);
        }
        // 第二次 drain 仍工作（delegate 恢复成功；行退避已到期）
        org.mockito.Mockito.doReturn("ok").when(delegate).send(any());
        try (java.sql.Connection c = dataSource().getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("UPDATE outbox SET next_retry_at = now() - interval '60 seconds'");
        }
        relay.tryDrainIfLeader();
        assertThat(countRows(null)).isZero();
    }

    @Test
    @DisplayName("drain-until-empty：单 poll 内循环 claim 直到清空（多 batch）")
    void drainsUntilEmpty() {
        // batch-size 默认 32，插 40 行 → 单 batch 不够 → drainUntilEmpty 循环 claim 第二次
        for (int i = 0; i < 40; i++) {
            insertPending("chat_message_save", 0);
        }

        relay.drainUntilEmpty();

        verify(delegate, times(40)).send(any());
        assertThat(countRows(null)).isZero();
    }

    @Test
    @DisplayName("claiming 超时回收：超时 claiming 行被 claim 并投递")
    void claimingTimeoutRecovered() {
        OutboxEntry row = new OutboxEntry();
        row.setTopic("chat_message_save");
        row.setPayload("\"x\"");
        row.setPayloadType(String.class.getName());
        row.setHeaders("{}");
        row.setStatus("claiming");
        row.setAttempts(0);
        row.setNextRetryAt(Instant.now());
        row.setCreatedAt(Instant.now().minusSeconds(600));
        row.setUpdatedAt(Instant.now().minusSeconds(600));   // 超 claimingTimeoutSeconds(300)
        mapper().insert(row);

        relay.drainUntilEmpty();

        verify(delegate).send(any());
        assertThat(countRows(null)).isZero();
    }

    @Test
    @DisplayName("未超时的 claiming 行不被回收")
    void freshClaimingNotRecovered() {
        OutboxEntry row = new OutboxEntry();
        row.setTopic("chat_message_save");
        row.setPayload("\"x\"");
        row.setPayloadType(String.class.getName());
        row.setHeaders("{}");
        row.setStatus("claiming");
        row.setAttempts(0);
        row.setNextRetryAt(Instant.now());
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        mapper().insert(row);

        relay.drainUntilEmpty();

        verify(delegate, times(0)).send(any());
        assertThat(countRows("claiming")).isEqualTo(1);
    }

    @Test
    @DisplayName("relay 重建 envelope：payload_type 反序列化 + tag/hashKey/headers(traceparent) 恢复")
    void rebuildsEnvelopeWithMetadata() {
        insertPending("chat_message_save", 0);
        // 直接插一行 usage payload 类验证 payload_type 驱动
        OutboxEntry usageRow = new OutboxEntry();
        usageRow.setTopic("usage_event_record");
        usageRow.setPayload("{\"eventId\":\"ev-9\",\"userId\":7,\"scene\":\"CHAT\","
            + "\"conversationId\":\"conv-9\",\"candidateId\":\"c-1\",\"promptTokens\":1,"
            + "\"completionTokens\":2,\"totalTokens\":3,\"estimated\":false,\"success\":true,\"durationMs\":4}");
        usageRow.setPayloadType(com.smart.rag.usage.UsageEventPayload.class.getName());
        usageRow.setTag("tag-u");
        usageRow.setHashKey("hk-u");
        usageRow.setDedupKey("dk-u");
        usageRow.setHeaders("{\"traceparent\":\"00-abc-01\",\"content-type\":\"application/json\"}");
        usageRow.setStatus("pending");
        usageRow.setAttempts(0);
        usageRow.setNextRetryAt(Instant.now().minusSeconds(60));
        usageRow.setCreatedAt(Instant.now().minusSeconds(60));
        usageRow.setUpdatedAt(Instant.now().minusSeconds(60));
        mapper().insert(usageRow);

        relay.drainUntilEmpty();

        ArgumentCaptor<MessageEnvelope<?>> captor = ArgumentCaptor.forClass(MessageEnvelope.class);
        verify(delegate, times(2)).send(captor.capture());
        MessageEnvelope<?> usageEnv = captor.getAllValues().stream()
            .filter(e -> "usage_event_record".equals(e.topic()))
            .findFirst().orElseThrow();
        assertThat(usageEnv.payload()).isInstanceOf(com.smart.rag.usage.UsageEventPayload.class);
        assertThat(usageEnv.tag()).isEqualTo("tag-u");
        assertThat(usageEnv.hashKey()).isEqualTo("hk-u");
        assertThat(usageEnv.deduplicationKey()).isEqualTo("dk-u");
        assertThat(usageEnv.headers().get("traceparent")).isEqualTo("00-abc-01");   // 存储值，非 relay 注入
        assertThat(usageEnv.timestamp()).isEqualTo(usageRow.getCreatedAt().toEpochMilli());
    }

    @Test
    @DisplayName("payload_type 类不存在 → 按投递失败路径处理（bump，最终 dead 而非静默）")
    void missingPayloadTypeBumpsThenDead() {
        OutboxEntry row = new OutboxEntry();
        row.setTopic("chat_message_save");
        row.setPayload("\"x\"");
        row.setPayloadType("com.smart.rag.NoSuchPayloadClass");
        row.setHeaders("{}");
        row.setStatus("pending");
        row.setAttempts(15);   // 下次失败即 dead
        row.setNextRetryAt(Instant.now().minusSeconds(60));
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        mapper().insert(row);

        relay.drainUntilEmpty();

        OutboxEntry after = reload(row.getId());
        assertThat(after.getStatus()).isEqualTo("dead");
        assertThat(after.getFailureReason()).contains("NoSuchPayloadClass");
    }
}
