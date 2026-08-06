package com.smart.rag.infrastructure.messaging.redis;

import com.smart.rag.infrastructure.messaging.MessagingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RedisStreamKeys} 单测（P2-10）：key 维度含 group；prefix/topic/group 组合。
 */
class RedisStreamKeysTest {

    private final MessagingProperties properties = new MessagingProperties(
        "SMART_RAG_", Duration.ofSeconds(30), null, null, null, null, null, null);

    @Test
    @DisplayName("默认前缀：主 stream 不含 group，retry/dlq 含 group")
    void defaultPrefixes() {
        RedisStreamKeys keys = new RedisStreamKeys(properties);

        assertEquals("stream:SMART_RAG_chat_message_save",
            keys.streamKey("chat_message_save"));
        assertEquals("dlq:SMART_RAG_chat_message_save:save-group",
            keys.dlqKey("chat_message_save", "save-group"));
        assertEquals("retry:SMART_RAG_chat_message_save:save-group",
            keys.retryHashKey("chat_message_save", "save-group"));
        assertEquals("retry-zset:SMART_RAG_chat_message_save:save-group",
            keys.retryZsetKey("chat_message_save", "save-group"));
    }

    @Test
    @DisplayName("同一 topic 不同 group 的 retry/dlq key 互不串扰（P2-10）")
    void groupScopedKeysDoNotCollide() {
        RedisStreamKeys keys = new RedisStreamKeys(properties);

        String topic = "rag_index_document";
        assertNotEquals(keys.dlqKey(topic, "index-group"), keys.dlqKey(topic, "other-group"));
        assertNotEquals(keys.retryHashKey(topic, "index-group"), keys.retryHashKey(topic, "other-group"));
        assertNotEquals(keys.retryZsetKey(topic, "index-group"), keys.retryZsetKey(topic, "other-group"));
        assertEquals(keys.streamKey(topic), keys.streamKey(topic));  // 主 stream 与 group 无关
    }

    @Test
    @DisplayName("自定义前缀生效")
    void customPrefixes() {
        MessagingProperties.RedisStreamConfig config = new MessagingProperties.RedisStreamConfig(
            "s:", "d:", "r:", "rz:", "c:", 0, 0, null, 0, null, null, null, 0, null, null, null);
        MessagingProperties customProps = new MessagingProperties(
            "PRE_", Duration.ofSeconds(30), null, null, null, null, config, null);
        RedisStreamKeys keys = new RedisStreamKeys(customProps);

        assertEquals("s:PRE_t", keys.streamKey("t"));
        assertEquals("d:PRE_t:g", keys.dlqKey("t", "g"));
        assertEquals("r:PRE_t:g", keys.retryHashKey("t", "g"));
        assertEquals("rz:PRE_t:g", keys.retryZsetKey("t", "g"));
    }
}
