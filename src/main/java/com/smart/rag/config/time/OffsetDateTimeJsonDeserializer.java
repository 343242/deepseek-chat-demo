package com.smart.rag.config.time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * {@link OffsetDateTime} 的 Jackson 反序列化器——委托 {@link TimeCodec#parse}，
 * 双口径解析（带偏移 ISO-8601 优先，无偏移按配置时区补），还原为配置时区的 {@code OffsetDateTime}。
 */
public final class OffsetDateTimeJsonDeserializer extends JsonDeserializer<OffsetDateTime> {

    private final TimeCodec codec;

    public OffsetDateTimeJsonDeserializer(TimeCodec codec) {
        this.codec = codec;
    }

    @Override
    public OffsetDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return OffsetDateTime.ofInstant(codec.parse(p.getValueAsString()), codec.zone());
    }
}
