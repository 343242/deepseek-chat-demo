package com.smart.rag.infrastructure.messaging;

/**
 * Message payload serialization — extensible for JSON, Protobuf, Avro, etc.
 */
public interface MessagePayloadCodec {
    byte[] encode(Object payload);

    <T> T decode(byte[] data, Class<T> type);
}
