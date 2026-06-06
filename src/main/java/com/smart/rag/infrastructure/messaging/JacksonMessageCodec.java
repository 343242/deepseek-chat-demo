package com.smart.rag.infrastructure.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.exception.PermanentConsumeException;
import com.smart.rag.infrastructure.exception.MessagePublishException;
import org.springframework.stereotype.Component;

/**
 * Jackson JSON message codec — reuses Spring's auto-configured ObjectMapper.
 * <p>
 * FAIL_ON_UNKNOWN_PROPERTIES=false: allows additive schema changes (new fields)
 * without breaking old consumers.
 */
@Component
public class JacksonMessageCodec implements MessagePayloadCodec {

    private final ObjectMapper objectMapper;

    public JacksonMessageCodec(ObjectMapper springObjectMapper) {
        this.objectMapper = springObjectMapper.copy()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public byte[] encode(Object payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new MessagePublishException("Failed to encode message payload", e);
        }
    }

    @Override
    public <T> T decode(byte[] data, Class<T> type) {
        try {
            return objectMapper.readValue(data, type);
        } catch (Exception e) {
            throw new PermanentConsumeException("Failed to decode message payload: " + type.getName(), e);
        }
    }
}
