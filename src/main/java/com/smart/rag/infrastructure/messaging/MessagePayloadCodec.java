package com.smart.rag.infrastructure.messaging;

import java.nio.ByteBuffer;

/**
 * Message payload serialization — extensible for JSON, Protobuf, Avro, etc.
 */
public interface MessagePayloadCodec {
    byte[] encode(Object payload);

    <T> T decode(byte[] data, Class<T> type);

    /** Convert ByteBuffer to byte array — shared utility for RocketMQ message body extraction. */
    static byte[] toByteArray(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
