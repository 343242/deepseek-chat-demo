package com.smart.rag.infrastructure.messaging.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.messaging.JacksonMessageCodec;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RedisStreamDeadLetterOperations} 单测（R5，Testcontainers）：scan/replay/count 三方法
 * 真正实现；DLQ MAXLEN 生效（P2-8）；多组扩展点（group 解析）。
 */
class RedisStreamDeadLetterOperationsTest extends AbstractRedisStreamTest {

    private static final String TOPIC = "chat_message_save";
    private static final String GROUP = "save-group";

    private RedisStreamDeadLetterOperations ops;

    @BeforeEach
    void setUp() {
        flushAll();
        ops = new RedisStreamDeadLetterOperations(dlqWriter(), business(),
            new JacksonMessageCodec(new ObjectMapper()), keys(), props(), t -> GROUP);
    }

    private Map<String, String> originalFields(String dedupKey) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("topic", TOPIC);
        fields.put("tag", "");
        fields.put("dedupKey", dedupKey);
        fields.put("hashKey", "");
        fields.put("headers", "{}");
        fields.put("payload", "\"dead-" + dedupKey + "\"");
        fields.put("bornTs", "1");
        fields.put("attempt", "3");
        fields.put("contentType", "application/json");
        return fields;
    }

    @Test
    @DisplayName("sendToDeadLetter → count/scan 可见，字段含元数据")
    void writeThenScanAndCount() {
        assertTrue(ops.sendToDeadLetter(TOPIC, GROUP, originalFields("k1"), "PERMANENT"));

        assertEquals(1, ops.deadLetterCount(TOPIC));

        List<MessageEnvelope<?>> scanned = ops.scanDeadLetters(TOPIC, 10);
        assertEquals(1, scanned.size());
        MessageEnvelope<?> envelope = scanned.getFirst();
        assertEquals(TOPIC, envelope.topic());          // originalTopic 还原
        assertEquals("dead-k1", envelope.payload());    // payload decode（Object → String）
        assertNotNull(envelope.id());
    }

    @Test
    @DisplayName("replayDeadLetter 回灌主 stream（原字段保留），DLQ 审计保留")
    void replayRoundTrip() {
        ops.sendToDeadLetter(TOPIC, GROUP, originalFields("k2"), "RETRY_EXHAUSTED");
        String dlqId = business().opsForStream().range(keys().dlqKey(TOPIC, GROUP), Range.unbounded())
            .getFirst().getId().getValue();

        ops.replayDeadLetter(TOPIC, dlqId);

        List<MapRecord<String, Object, Object>> main = business().opsForStream()
            .range(keys().streamKey(TOPIC), Range.unbounded());
        assertEquals(1, main.size());
        Map<?, ?> fields = main.getFirst().getValue();
        assertEquals("k2", fields.get("dedupKey"));
        assertEquals("\"dead-k2\"", fields.get("payload"));
        assertEquals("3", fields.get("attempt"));
        // DLQ 条目保留（审计）
        assertEquals(1, ops.deadLetterCount(TOPIC));
    }

    @Test
    @DisplayName("replay 不存在的 messageId 抛 NOT_FOUND")
    void replayMissing_throws() {
        assertThrows(com.smart.rag.infrastructure.exception.ServiceException.class,
            () -> ops.replayDeadLetter(TOPIC, "9999999999999-0"));
    }

    @Test
    @DisplayName("未注册 group 的 topic 抛 INVALID_GROUP（1:1 解析）")
    void unknownTopic_throws() {
        RedisStreamDeadLetterOperations noGroupOps = new RedisStreamDeadLetterOperations(dlqWriter(),
            business(), new JacksonMessageCodec(new ObjectMapper()), keys(), props(), t -> null);
        assertThrows(com.smart.rag.infrastructure.exception.ServiceException.class,
            () -> noGroupOps.deadLetterCount("unknown_topic"));
    }

    @Test
    @DisplayName("DLQ MAXLEN 生效（P2-8）：超 dlq-trim-threshold 后旧条目被裁剪")
    void dlqTrimThreshold_limitsGrowth() {
        MessagingProperties trimProps = customProps(new MessagingProperties.RedisStreamConfig(
            null, null, null, null, null, 0, 50, null, 0, null, null, null, 0, null, null, null));
        RedisStreamDeadLetterWriter trimWriter = new RedisStreamDeadLetterWriter(business(), trimProps);

        for (int i = 0; i < 100; i++) {
            assertTrue(trimWriter.sendToDeadLetter(keys().dlqKey(TOPIC, GROUP), TOPIC, GROUP,
                originalFields("k" + i), "PERMANENT"));
        }

        long xlen = business().opsForStream().size(keys().dlqKey(TOPIC, GROUP));
        assertTrue(xlen < 100, "DLQ 未被裁剪: xlen=" + xlen);
        assertTrue(xlen >= 40, "DLQ 裁剪过度: xlen=" + xlen);
    }
}
