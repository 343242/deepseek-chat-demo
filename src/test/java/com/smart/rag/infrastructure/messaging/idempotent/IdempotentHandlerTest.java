package com.smart.rag.infrastructure.messaging.idempotent;

import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.MessagePayloadCodec;
import com.smart.rag.infrastructure.messaging.MessagingMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotentHandlerTest {

    private MessageHandler<String> delegate;
    private StringRedisTemplate redis;
    private MessagingMetrics metrics;
    private MessageHandler<String> wrapped;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        delegate = mock(MessageHandler.class);
        redis = mock(StringRedisTemplate.class);
        metrics = mock(MessagingMetrics.class);
        wrapped = IdempotentHandler.wrap(delegate, "test-topic", redis, 300, metrics);
    }

    private MessageEnvelope<String> envelope(String dedupKey) {
        return new MessageEnvelope<>("id1", "test-topic", null, "payload",
            null, dedupKey, Map.of(), System.currentTimeMillis());
    }

    @Nested
    @DisplayName("First delivery")
    class FirstDelivery {

        @Test
        @DisplayName("passes to delegate when not duplicate")
        void passesToDelegate() {
            when(redis.execute(any(RedisScript.class), any(), any()))
                .thenReturn(0L);
            wrapped.onMessage(envelope("key1"));
            verify(delegate).onMessage(any());
        }
    }

    @Nested
    @DisplayName("Duplicate detection")
    class DuplicateDetection {

        @Test
        @DisplayName("skips duplicate message without calling delegate")
        void skipsDuplicate() {
            when(redis.execute(any(RedisScript.class), any(), any()))
                .thenReturn(1L);
            wrapped.onMessage(envelope("key1"));
            verifyNoInteractions(delegate);
        }
    }

    @Nested
    @DisplayName("Null/empty deduplication key")
    class NullOrEmptyKey {

        @Test
        @DisplayName("null key bypasses Redis check")
        void nullKey_bypassesRedis() {
            wrapped.onMessage(envelope(null));
            verify(delegate).onMessage(any());
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("empty key bypasses Redis check")
        void emptyKey_bypassesRedis() {
            wrapped.onMessage(envelope(""));
            verify(delegate).onMessage(any());
            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("Redis failure")
    class RedisFailure {

        @Test
        @DisplayName("before mark: delegates to handler with degraded metric")
        void beforeMark_delegatesToHandler() {
            when(redis.execute(any(RedisScript.class), any(), any()))
                .thenThrow(new RuntimeException("Redis down"));
            wrapped.onMessage(envelope("key1"));
            verify(delegate).onMessage(any());
            verify(metrics).recordIdempotentDegraded("test-topic");
        }

        @Test
        @DisplayName("after mark: handler failure deletes key and rethrows")
        void afterMark_handlerFails_deletesKey() {
            when(redis.execute(any(RedisScript.class), any(), any()))
                .thenReturn(0L);
            doThrow(new RuntimeException("business error")).when(delegate).onMessage(any());
            when(redis.delete(anyString())).thenReturn(true);

            assertThrows(RuntimeException.class, () -> wrapped.onMessage(envelope("key1")));
            verify(redis).delete(contains("messaging:idempotent:"));
        }

        @Test
        @DisplayName("Redis failure + handler failure: rethrows handler error")
        void redisAndHandlerBothFail_rethrows() {
            when(redis.execute(any(RedisScript.class), any(), any()))
                .thenThrow(new RuntimeException("Redis down"));
            doThrow(new RuntimeException("handler error")).when(delegate).onMessage(any());

            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> wrapped.onMessage(envelope("key1")));
            assertEquals("handler error", ex.getMessage());
        }
    }
}
