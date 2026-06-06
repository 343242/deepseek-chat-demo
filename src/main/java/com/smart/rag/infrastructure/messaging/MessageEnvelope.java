package com.smart.rag.infrastructure.messaging;

import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * Message envelope — transport-decoupled message wrapper.
 *
 * @param id                transport-assigned ID, null before send
 * @param topic             target Topic
 * @param tag               Broker-side filter tag, null = no tag
 * @param payload           business payload
 * @param hashKey           ordered message partition key, null = unordered
 * @param deduplicationKey  consumer-side idempotent key (stable across retries)
 * @param headers           extension headers (traceId, contentType, etc.)
 * @param timestamp         creation timestamp
 */
public record MessageEnvelope<T>(
    @Nullable String id,
    String topic,
    @Nullable String tag,
    T payload,
    @Nullable String hashKey,
    @Nullable String deduplicationKey,
    Map<String, String> headers,
    long timestamp
) {
    public static <T> MessageEnvelope<T> of(String topic, T payload) {
        return new MessageEnvelope<>(null, topic, null, payload, null, null, Map.of(),
            System.currentTimeMillis());
    }

    public static <T> MessageEnvelope<T> of(String topic, String tag, T payload) {
        return new MessageEnvelope<>(null, topic, tag, payload, null, null, Map.of(),
            System.currentTimeMillis());
    }

    /**
     * Create an ordered message. Same hashKey routes to the same partition (5.x messageGroup).
     * Different hashKeys have no ordering guarantee.
     */
    public static <T> MessageEnvelope<T> ordered(String topic, T payload, String hashKey) {
        return new MessageEnvelope<>(null, topic, null, payload, hashKey, null, Map.of(),
            System.currentTimeMillis());
    }

    /**
     * Create a deduplicated message. DeduplicationKey is used for consumer-side idempotency
     * (DB unique constraint / business natural key).
     */
    public static <T> MessageEnvelope<T> deduplicated(String topic, T payload, String deduplicationKey) {
        return new MessageEnvelope<>(null, topic, null, payload, null, deduplicationKey, Map.of(),
            System.currentTimeMillis());
    }
}
