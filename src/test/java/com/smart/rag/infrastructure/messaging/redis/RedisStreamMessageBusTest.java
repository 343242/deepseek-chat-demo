package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.exception.MessagePublishException;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import com.smart.rag.infrastructure.messaging.TracePropagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RedisStreamMessageBus} 单测（mock streamOps）：send 返回 entry ID；XADD 字段正确
 * （含 attempt=0、traceparent 冻结点）；熔断 OPEN 抛异常。
 */
class RedisStreamMessageBusTest {

    private StringRedisTemplate businessTemplate;
    private StreamOperations<String, Object, Object> streamOps;
    private MessagePayloadCodec codec;
    private RedisStreamMessageBus bus;
    private MessagingProperties properties;
    private RedisStreamKeys keys;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new MessagingProperties("T_", Duration.ofSeconds(30), null, null,
            new MessagingProperties.CircuitBreakerConfig(1, 30_000), null, null);
        businessTemplate = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(businessTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1690000000000-0"));
        codec = mock(MessagePayloadCodec.class);
        when(codec.encode(any())).thenReturn("ENC".getBytes());
        keys = new RedisStreamKeys(properties);

        RedisStreamConsumerConnections connections = mock(RedisStreamConsumerConnections.class);
        bus = new RedisStreamMessageBus(properties, businessTemplate, codec, null, TracePropagator.NO_OP,
            null, connections, keys, mock(RedisStreamDeadLetterWriter.class),
            mock(RetrySweeper.class), mock(PelRecoverySweeper.class), mock(StreamTrimTask.class));
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> capturedFields() {
        ArgumentCaptor<MapRecord<String, String, String>> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        return captor.getValue().getValue();
    }

    @Test
    @DisplayName("send 返回 stream entry ID，XADD 字段含 attempt=0 与完整元数据")
    void send_returnsEntryIdAndWritesFields() {
        String id = bus.send(MessageEnvelope.of("orders", "payload"));

        assertEquals("1690000000000-0", id);
        verify(streamOps).add(any(MapRecord.class));

        Map<?, ?> fields = capturedFields();
        assertEquals("orders", fields.get("topic"));
        assertEquals("0", fields.get("attempt"));        // P0-2 基线
        assertEquals("", fields.get("tag"));
        assertEquals("", fields.get("dedupKey"));
        assertEquals("", fields.get("hashKey"));
        assertEquals("application/json", fields.get("contentType"));
        assertEquals("ENC", fields.get("payload"));
        assertEquals("{}", fields.get("headers"));
        assertNotNull(fields.get("bornTs"));
    }

    @Test
    @DisplayName("XADD key = stream:{prefix}{topic}，无 MAXLEN（P1-5 trim 由 StreamTrimTask 负责）")
    void send_usesStreamKeyWithoutMaxlen() {
        bus.send(MessageEnvelope.of("orders", "payload"));
        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        assertEquals("stream:T_orders", captor.getValue().getStream());
    }

    @Test
    @DisplayName("熔断 OPEN 后 send 抛 MessagePublishException，isCircuitBreakerOpen 为 true")
    void circuitBreakerOpen_sendFailsFast() {
        when(streamOps.add(any(MapRecord.class)))
            .thenThrow(new RuntimeException("redis down"))
            .thenThrow(new RuntimeException("redis down"));

        assertThrows(MessagePublishException.class,
            () -> bus.send(MessageEnvelope.of("orders", "payload")));   // 失败 1 次 → OPEN（threshold=1）

        MessagePublishException e = assertThrows(MessagePublishException.class,
            () -> bus.send(MessageEnvelope.of("orders", "payload")));
        assertTrue(e.getMessage().contains("Circuit breaker OPEN"));

        assertTrue(bus.isCircuitBreakerOpen("orders"));
        assertEquals("open", bus.circuitBreakerState().get("orders"));
    }

    @Test
    @DisplayName("traceparent 冻结点：已存在不覆盖，缺失才注入")
    void traceparent_existsWins() {
        TracePropagator injecting = new TracePropagator() {
            @Override public Map<String, String> inject() { return Map.of("traceparent", "injected"); }
            @Override public void restore(Map<String, String> headers) { }
            @Override public void clear() { }
        };
        RedisStreamConsumerConnections connections = mock(RedisStreamConsumerConnections.class);
        RedisStreamMessageBus injectingBus = new RedisStreamMessageBus(properties, businessTemplate, codec,
            null, injecting, null, connections, keys, mock(RedisStreamDeadLetterWriter.class),
            mock(RetrySweeper.class), mock(PelRecoverySweeper.class), mock(StreamTrimTask.class));

        // 已存在 traceparent（relay 投递场景）→ 不覆盖
        MessageEnvelope<String> withTrace = new MessageEnvelope<>(null, "orders", null, "p",
            null, null, Map.of("traceparent", "original"), 1L);
        injectingBus.send(withTrace);
        Map<?, ?> fields = capturedFields();
        String headersJson = (String) fields.get("headers");
        assertTrue(headersJson.contains("original"), headersJson);
        assertFalse(headersJson.contains("injected"), headersJson);

        // 无 traceparent（直接调用）→ 注入
        reset(streamOps);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));
        injectingBus.send(MessageEnvelope.of("orders", "p"));
        ArgumentCaptor<MapRecord<String, String, String>> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        String headersJson2 = captor.getValue().getValue().get("headers");
        assertTrue(headersJson2.contains("injected"), headersJson2);
    }

    @Test
    @DisplayName("非法 topic 抛 ClientException 且不计入熔断失败")
    void invalidTopic_throwsClientException() {
        assertThrows(com.smart.rag.infrastructure.exception.ClientException.class,
            () -> bus.send(MessageEnvelope.of("bad topic", "data")));
        verify(streamOps, never()).add(any(MapRecord.class));
        assertFalse(bus.isCircuitBreakerOpen("bad topic"));
    }
}
