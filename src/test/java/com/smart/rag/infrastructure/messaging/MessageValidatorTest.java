package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageValidatorTest {

    private MessagingProperties properties;
    private MessagePayloadCodec codec;
    private MessageValidator validator;

    @BeforeEach
    void setUp() {
        properties = new MessagingProperties("T_", Duration.ofSeconds(30),
            null, null, null, null, null);
        codec = mock(MessagePayloadCodec.class);
        validator = new MessageValidator(properties, codec);
    }

    @Nested
    @DisplayName("validateAndEncode")
    class ValidateAndEncode {

        @Test
        @DisplayName("valid message encodes and returns payload bytes")
        void validMessage_encodesPayload() {
            when(codec.encode("payload")).thenReturn("\"payload\"".getBytes());
            byte[] result = validator.validateAndEncode(MessageEnvelope.of("orders", "payload"));
            assertArrayEquals("\"payload\"".getBytes(), result);
            verify(codec).encode("payload");
        }

        @Test
        @DisplayName("topic exceeding 128 chars with prefix throws ClientException")
        void topicExceeds128_throws() {
            String topic = "x".repeat(127);
            assertThrows(ClientException.class,
                () -> validator.validateAndEncode(MessageEnvelope.of(topic, "data")));
        }

        @ParameterizedTest
        @ValueSource(strings = {"bad topic", "a.b", "a/b", ""})
        @DisplayName("invalid topic patterns throw ClientException")
        void invalidTopics_throw(String topic) {
            assertThrows(ClientException.class,
                () -> validator.validateAndEncode(MessageEnvelope.of(topic, "data")));
        }

        @Test
        @DisplayName("invalid tag characters throw ClientException")
        void invalidTag_throws() {
            var msg = new MessageEnvelope<>(null, "topic", "bad tag!", "data",
                null, null, Map.of(), 0);
            assertThrows(ClientException.class, () -> validator.validateAndEncode(msg));
        }

        @Test
        @DisplayName("null tag is allowed")
        void nullTag_allowed() {
            when(codec.encode("data")).thenReturn(new byte[1]);
            assertDoesNotThrow(() -> validator.validateAndEncode(MessageEnvelope.of("t", "data")));
        }

        @Test
        @DisplayName("valid tag passes")
        void validTag_passes() {
            when(codec.encode("data")).thenReturn(new byte[1]);
            var msg = new MessageEnvelope<>(null, "t", "valid_tag", "data",
                null, null, Map.of(), 0);
            assertDoesNotThrow(() -> validator.validateAndEncode(msg));
        }
    }

    @Nested
    @DisplayName("validateTopicPrefix")
    class ValidateTopicPrefix {

        @Test
        @DisplayName("valid prefix passes")
        void validPrefix() {
            assertDoesNotThrow(() -> MessageValidator.validateTopicPrefix("APP_"));
        }

        @Test
        @DisplayName("null prefix passes")
        void nullPrefix() {
            assertDoesNotThrow(() -> MessageValidator.validateTopicPrefix(null));
        }

        @Test
        @DisplayName("empty prefix passes")
        void emptyPrefix() {
            assertDoesNotThrow(() -> MessageValidator.validateTopicPrefix(""));
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "a.b", "a/b"})
        @DisplayName("invalid prefix throws ClientException")
        void invalidPrefix_throws(String prefix) {
            assertThrows(ClientException.class, () -> MessageValidator.validateTopicPrefix(prefix));
        }
    }
}
