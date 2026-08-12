package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.JacksonMessageCodec;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * OutboxMessageBus.insert() 的真 PG 回归测试——锁死「发布侧必须显式写入 created_at/updated_at」。
 * <p>
 * 既有 send()/sendAsync() 单测全程 mock OutboxMapper，INSERT 从不触达真库，V23 DDL 的 NOT NULL
 * 约束无法生效，导致 insert() 漏写 createdAt/updatedAt 的 bug 从 5cd0f5b 潜伏到联调首条
 * chat_usage_record 才暴露（PSQLException: null value in column "created_at"）。本用例对真 PG
 * 跑 bus.send()：修复前直接抛 OUTBOX_INSERT_FAILED，修复后行落库且时间戳非空。
 * <p>
 * gate OPEN 让 tryImmediate 提前返回——不触发即时投递，行留 PG 供 reload 校验，无异步竞态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxMessageBusInsertTest extends AbstractOutboxTest {

    @Mock
    private MessageBus delegate;

    @Mock
    private SharedCircuitBreakerGate cbGate;

    private OutboxMessageBus bus;

    @BeforeEach
    void setUp() {
        clearOutbox();
        MessagePayloadCodec codec = new JacksonMessageCodec(new com.fasterxml.jackson.databind.ObjectMapper());
        when(cbGate.isOpen(any())).thenReturn(true);
        MessagingProperties properties = new MessagingProperties("T_", Duration.ofSeconds(30),
            null, null, null, null, null, null);
        bus = new OutboxMessageBus(delegate, mapper(), codec, cbGate, properties,
            new OutboxMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    @DisplayName("send() 落库：created_at/updated_at 非空（不再违约 NOT NULL）")
    void sendPersistsRowWithTimestamps() {
        String outboxId = bus.send(MessageEnvelope.of("chat_usage_record", "usage"));

        // 修复前：上一行已在 outboxMapper.insert 处抛 MessagingException(OUTBOX_INSERT_FAILED)。
        assertThat(outboxId).isNotNull();
        OutboxEntry row = mapper().selectById(Long.valueOf(outboxId));
        assertThat(row).as("INSERT 成功后行应落库").isNotNull();
        assertThat(row.getCreatedAt()).as("createdAt 必须由 insert() 显式赋值").isNotNull();
        assertThat(row.getUpdatedAt()).as("updatedAt 必须由 insert() 显式赋值").isNotNull();
        assertThat(row.getNextRetryAt()).isNotNull();
        assertThat(row.getStatus()).isEqualTo("pending");
        assertThat(row.getAttempts()).isZero();
    }
}
