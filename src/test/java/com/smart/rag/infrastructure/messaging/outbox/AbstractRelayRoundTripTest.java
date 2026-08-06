package com.smart.rag.infrastructure.messaging.outbox;

import com.smart.rag.infrastructure.messaging.BackoffSchedule;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * relay 重建 round-trip 测试基座：真实 PG 行 → relay 重建 envelope → mock delegate 捕获。
 * 各子类分别断言一个传输元数据维度（payload_type / tag / hash_key / traceparent）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class AbstractRelayRoundTripTest extends AbstractOutboxTest {

    @Mock
    protected MessageBus delegate;

    protected MessagePayloadCodec codec;
    protected OutboxRelay relay;
    protected final AtomicReference<MessageEnvelope<?>> captured = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        clearOutbox();
        codec = new com.smart.rag.infrastructure.messaging.JacksonMessageCodec(
            new com.fasterxml.jackson.databind.ObjectMapper());
        when(delegate.send(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return "id";
        });
        MessagingProperties properties = new MessagingProperties("T_", Duration.ofSeconds(30),
            null, null, null, null, null, null);
        relay = new OutboxRelay(mapper(), tx(), delegate, codec,
            new SharedCircuitBreakerGate(null, null, properties) {
                @Override
                public boolean isOpen(String topic) { return false; }
            },
            new BackoffSchedule(null), properties,
            new RedissonLeadership(null, "k", Duration.ofSeconds(5)),
            new OutboxMetrics(new SimpleMeterRegistry()), new SimpleMeterRegistry());
    }

    /** 插一行含完整元数据的 pending 行。 */
    protected OutboxEntry insertFullRow(String payloadType, String payloadJson,
                                        String tag, String hashKey, String dedupKey,
                                        Map<String, String> headers) {
        OutboxEntry e = new OutboxEntry();
        e.setTopic("rag_index_document");
        e.setPayload(payloadJson);
        e.setPayloadType(payloadType);
        e.setTag(tag);
        e.setHashKey(hashKey);
        e.setDedupKey(dedupKey);
        e.setHeaders(OutboxMessageBus.headersJson(headers));
        e.setStatus("pending");
        e.setAttempts(0);
        e.setNextRetryAt(Instant.now().minusSeconds(60));
        e.setCreatedAt(Instant.now().minusSeconds(60));
        e.setUpdatedAt(Instant.now().minusSeconds(60));
        mapper().insert(e);
        return e;
    }

    protected void drainAndCapture() {
        relay.drainUntilEmpty();
        assertThatCaptured();
    }

    private void assertThatCaptured() {
        if (captured.get() == null) {
            throw new AssertionError("relay 未投递（无捕获）");
        }
    }

    protected List<OutboxEntry> allRows() {
        return mapper().selectList(null);
    }
}
